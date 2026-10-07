package com.granatum.core

import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.port.ResultadoReconocimiento
import com.granatum.core.scheduling.ReintentoReconocimientoJob
import com.granatum.core.service.FacturaLecturas
import com.granatum.core.service.FicheroSubido
import com.granatum.core.service.SubidaFacturas
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * Pending invoices are not forgotten: after a restart or a failure, the
 * scheduled job sends them again (SC-008) - but not for ever. After three
 * failed attempts an invoice is left for manual entry: one the model always
 * refuses would otherwise be sent, and paid for, every five minutes.
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
@Import(ConReconocedorFalso::class)
class ReintentoReconocimientoIT {

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

    @Autowired lateinit var subida: SubidaFacturas
    @Autowired lateinit var job: ReintentoReconocimientoJob
    @Autowired lateinit var falso: ReconocedorFalso
    @Autowired lateinit var lecturas: FacturaLecturas
    @Autowired lateinit var dataSource: DataSource

    private val fallo = { _: com.granatum.core.domain.port.DocumentoParaEnviar ->
        ResultadoReconocimiento.Fallida("ERROR_API", ReconocedorFalso.MODELO)
    }

    @BeforeEach
    fun reiniciar() = falso.reiniciar()

    private fun subirQueFalla(): UUID {
        falso.guion = fallo
        val id = subida.subir(listOf(FicheroSubido(null, Muestras.pdf())), UUID.randomUUID()).single().facturaId!!
        esperarIntentos(id, 1)
        return id
    }

    private fun intentos(id: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM factura_reconocimientos WHERE factura_id = ?").use { s ->
            s.setObject(1, id); s.executeQuery().use { r -> r.next(); r.getInt(1) }
        }
    }

    private fun esperarIntentos(id: UUID, n: Int) {
        val limite = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < limite && intentos(id) < n) Thread.sleep(50)
        Thread.sleep(100)
    }

    private fun envejecer(id: UUID) = dataSource.connection.use { c ->
        c.prepareStatement("UPDATE facturas SET subida_en = now() - interval '1 hour' WHERE id = ?").use { s ->
            s.setObject(1, id); s.executeUpdate()
        }
    }

    @Test
    fun `an old pending invoice is picked up again`() {
        val id = subirQueFalla()
        envejecer(id)
        falso.guion = { ReconocedorFalso.reconocida(ReconocedorFalso.propuesta()) }

        job.reintentar()
        esperarIntentos(id, 2)

        assertEquals(EstadoFactura.BORRADOR, lecturas.estado(id))
    }

    @Test
    fun `a recent pending invoice is left alone`() {
        val id = subirQueFalla()
        val llamadas = falso.llamadas.size

        job.reintentar()
        Thread.sleep(300)

        assertEquals(llamadas, falso.llamadas.size)
        assertEquals(1, intentos(id))
    }

    @Test
    fun `after three failed attempts it is left for manual entry`() {
        val id = subirQueFalla()
        envejecer(id)
        job.reintentar(); esperarIntentos(id, 2)
        job.reintentar(); esperarIntentos(id, 3)
        assertEquals(3, intentos(id))

        job.reintentar()
        Thread.sleep(300)

        assertEquals(3, intentos(id), "no fourth paid attempt")
        assertEquals(EstadoFactura.PENDIENTE_RECONOCER, lecturas.estado(id), "still there, to be filled in by hand")
    }
}
