package com.granatum.core

import com.granatum.core.domain.exception.FacturaDuplicadaException
import com.granatum.core.service.EmpresaService
import com.granatum.core.service.FacturaLecturas
import com.granatum.core.service.RevisionFacturas
import com.granatum.core.service.SondaConcurrencia
import com.granatum.core.service.SubidaFacturas
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Two photos of the same invoice cannot both count (FR-014, SC-006, research.md
 * D-015) - not even confirmed at the same instant from two threads.
 *
 * ## Validated by mutation (2026-10-08), in layers
 *
 * Three things stand in the way of a double confirmation, and the test shows
 * which ones are load-bearing:
 *
 * 1. **The service's duplicate check removed**: still green. The partial
 *    unique index refuses the second one, and the service turns that into
 *    `FACTURA_DUPLICADA`.
 * 2. **The unique index removed** (from V18): still green. Both confirmations
 *    lock the same quarter row (`SELECT ... FOR UPDATE`, D-016), so the second
 *    runs only after the first commits, and its check sees it.
 * 3. **Index and quarter lock both removed**: red - the two threads pass the
 *    check together (the probe makes them wait for each other) and both
 *    commit. So each of the two guarantees holds on its own.
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
@Import(ConReconocedorFalso::class, DuplicadoLogicoIT.ConSondaDeEncuentro::class)
class DuplicadoLogicoIT {

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

    /**
     * Makes two confirmations wait for each other right after their checks, so
     * that without a lock they would both pass them. With the lock, the second
     * never gets there while the first holds it: the wait times out and the
     * first carries on.
     */
    class SondaDeEncuentro : SondaConcurrencia {
        @Volatile var barrera: CyclicBarrier? = null
        override fun trasComprobar(facturaId: UUID) {
            try {
                barrera?.await(2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                // Alone at the meeting point: the other is waiting on the lock.
            }
        }
    }

    @TestConfiguration
    class ConSondaDeEncuentro {
        @Bean @Primary fun sondaDeEncuentro() = SondaDeEncuentro()
    }

    @Autowired lateinit var revision: RevisionFacturas
    @Autowired lateinit var subida: SubidaFacturas
    @Autowired lateinit var lecturas: FacturaLecturas
    @Autowired lateinit var empresa: EmpresaService
    @Autowired lateinit var falso: ReconocedorFalso
    @Autowired lateinit var sonda: SondaDeEncuentro
    @Autowired lateinit var dataSource: DataSource

    private val e by lazy { EscenarioFacturas(subida, lecturas, falso, empresa, dataSource) }

    @BeforeEach
    fun preparar() {
        falso.reiniciar()
        sonda.barrera = null
        e.configurarEmpresa()
    }

    private fun dosFotosDeLaMismaFactura(): Pair<UUID, UUID> {
        val propuesta = ReconocedorFalso.propuesta(numero = "D-${UUID.randomUUID().toString().take(8)}")
        return e.borrador(propuesta) to e.borrador(propuesta)
    }

    private fun confirmadas(a: UUID, b: UUID): Int =
        e.texto("SELECT count(*) FROM facturas WHERE id IN (?, ?) AND estado = 'CONFIRMADA'", a, b)!!.toInt()

    @Test
    fun `the second photo of a confirmed invoice cannot be confirmed`() {
        val (a, b) = dosFotosDeLaMismaFactura()
        revision.confirmar(a, lecturas.factura(a).version, e.admin)
        assertFailsWith<FacturaDuplicadaException> { revision.confirmar(b, lecturas.factura(b).version, e.admin) }
        assertEquals(1, confirmadas(a, b))
    }

    @Test
    fun `confirming both at the same instant still leaves one`() {
        val (a, b) = dosFotosDeLaMismaFactura()
        sonda.barrera = CyclicBarrier(2)
        val hilos = Executors.newFixedThreadPool(2)
        val futuros = listOf(a, b).map { id ->
            hilos.submit<Throwable?> {
                try { revision.confirmar(id, lecturas.factura(id).version, e.admin); null } catch (t: Throwable) { t }
            }
        }
        val resultados = futuros.map { it.get(30, TimeUnit.SECONDS) }
        hilos.shutdown()

        assertEquals(1, confirmadas(a, b), "exactly one of the two counts")
        assertEquals(1, resultados.count { it is FacturaDuplicadaException }, "$resultados")
    }
}
