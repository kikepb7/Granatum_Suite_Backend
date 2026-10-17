package com.granatum.core

import com.granatum.core.domain.contract.MaterialInexistenteException
import com.granatum.core.domain.contract.StockInsuficienteException
import com.granatum.core.domain.contract.StockMateriales
import com.granatum.core.domain.exception.MaterialEnUsoException
import com.granatum.core.domain.type.Almacen
import com.granatum.core.domain.type.TipoCambioHistorial
import com.granatum.core.infrastructure.database.entities.CategoriaEntity
import com.granatum.core.infrastructure.database.entities.DimensionesEmbeddable
import com.granatum.core.infrastructure.database.entities.LocalizacionEmbeddable
import com.granatum.core.infrastructure.database.entities.MaterialEntity
import com.granatum.core.infrastructure.database.repositories.CategoriaRepository
import com.granatum.core.infrastructure.database.repositories.HistorialMaterialRepository
import com.granatum.core.infrastructure.database.repositories.MaterialRepository
import com.granatum.core.service.MaterialService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The inventory side of `StockMateriales` (feature 011) against a real Postgres: withdrawals
 * are atomic, the stock never goes negative and every movement lands in the history.
 *
 * Not `@Transactional`: the concurrency test needs each withdrawal in its own transaction,
 * committed for real, as it would be in production.
 */
@Testcontainers
@SpringBootTest(classes = [InventoryTestApplication::class])
class StockMaterialesIT {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
        }
    }

    @Autowired lateinit var sut: StockMateriales
    @Autowired lateinit var categoriaRepository: CategoriaRepository
    @Autowired lateinit var materialRepository: MaterialRepository
    @Autowired lateinit var historialRepository: HistorialMaterialRepository
    @Autowired lateinit var materialService: MaterialService
    @Autowired lateinit var usoMateriales: UsoMaterialesDoble
    @Autowired lateinit var transacciones: TransactionTemplate

    private val usuario = UUID.randomUUID()

    private fun material(cantidad: Int): UUID {
        val categoria = categoriaRepository.save(CategoriaEntity(nombre = "Jarrones ${UUID.randomUUID()}", descripcion = null))
        return materialRepository.save(
            MaterialEntity(
                categoria = categoria,
                descripcion = "Jarrón cilíndrico",
                dimensiones = DimensionesEmbeddable(alto = BigDecimal("40.00"), ancho = BigDecimal("12.00")),
                cantidad = cantidad,
                localizacion = LocalizacionEmbeddable(almacen = Almacen.A, seccion = 2)
            )
        ).id
    }

    private fun cantidad(id: UUID) = materialRepository.cantidadDe(id)

    @Test
    fun `When units are withdrawn then the stock drops and the history names the reason`() {
        val id = material(cantidad = 8)

        val actual = transacciones.execute { sut.retirar(id, 5, usuario, "Reservado para el evento «Evento 1»") }!!

        assertEquals(expected = 3, actual = actual.cantidad)
        assertEquals(expected = 3, actual = cantidad(id))
        val entrada = historialRepository.findAllByMaterialIdOrderByFechaDesc(id).single()
        assertEquals(expected = TipoCambioHistorial.CANTIDAD, actual = entrada.tipoCambio)
        assertEquals(expected = "8" to "3", actual = entrada.valorAnterior to entrada.valorNuevo)
        assertEquals(expected = "Reservado para el evento «Evento 1»", actual = entrada.motivo)
        assertEquals(expected = usuario, actual = entrada.usuarioId)
    }

    @Test
    fun `When there are not enough units then nothing changes and the available amount is reported`() {
        val id = material(cantidad = 3)

        val actual = assertFailsWith<StockInsuficienteException> { transacciones.execute { sut.retirar(id, 4, usuario, "x") } }

        assertEquals(expected = 3, actual = actual.disponible)
        assertEquals(expected = 3, actual = cantidad(id))
        assertTrue(historialRepository.findAllByMaterialIdOrderByFechaDesc(id).isEmpty())
    }

    @Test
    fun `When the material does not exist then withdrawing fails and returning is a no-op`() {
        val inexistente = UUID.randomUUID()

        assertFailsWith<MaterialInexistenteException> { transacciones.execute { sut.retirar(inexistente, 1, usuario, "x") } }
        assertFalse(transacciones.execute { sut.devolver(inexistente, 1, usuario, "x") }!!)
    }

    @Test
    fun `When units are returned then the stock grows and the history records it`() {
        val id = material(cantidad = 2)

        val actual = transacciones.execute { sut.devolver(id, 5, usuario, "Devuelto del evento «Evento 1»") }!!

        assertTrue(actual)
        assertEquals(expected = 7, actual = cantidad(id))
        assertEquals(
            expected = "2" to "7",
            actual = historialRepository.findAllByMaterialIdOrderByFechaDesc(id).single().let { it.valorAnterior to it.valorNuevo }
        )
    }

    @Test
    fun `When many withdrawals race for the last units then the stock never goes negative`() {
        val id = material(cantidad = 10)
        val intentos = 25
        val exitos = AtomicInteger()
        val salida = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)

        repeat(intentos) {
            pool.submit {
                salida.await()
                runCatching { transacciones.execute { sut.retirar(id, 1, usuario, "Carrera") } }
                    .onSuccess { exitos.incrementAndGet() }
            }
        }
        salida.countDown()
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        assertEquals(expected = 10, actual = exitos.get())
        assertEquals(expected = 0, actual = cantidad(id))
    }

    @Test
    fun `When a material is out at an event then the inventory refuses to delete it`() {
        val id = material(cantidad = 1)
        usoMateriales.enUso += id

        assertFailsWith<MaterialEnUsoException> { materialService.delete(id) }

        assertEquals(expected = 1, actual = cantidad(id))
        usoMateriales.enUso -= id
    }

    @Test
    fun `When listing for an event then every material comes with its free units and location`() {
        val id = material(cantidad = 4)

        val actual = sut.disponibles().single { it.id == id }

        assertEquals(expected = 4, actual = actual.cantidad)
        assertEquals(expected = "A" to 2, actual = actual.almacen to actual.seccion)
        assertEquals(expected = setOf(id), actual = sut.resumen(listOf(id, UUID.randomUUID())).keys)
    }
}
