package com.granatum.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.granatum.core.domain.model.Periodo
import com.granatum.core.service.EmpresaService
import com.granatum.core.service.FacturaLecturas
import com.granatum.core.service.ReportesFacturacion
import com.granatum.core.service.RevisionFacturas
import com.granatum.core.service.SondaConcurrencia
import com.granatum.core.service.SubidaFacturas
import com.granatum.core.service.Trimestres
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
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Research D-016, SC-009: closing a quarter and changing one of its invoices
 * cannot interleave, so a closed quarter always equals its snapshot.
 *
 * The race is forced, not hoped for (finding T1): a probe stops a discard of a
 * confirmed invoice right after its checks - quarter open - and a close is
 * started meanwhile. With the lock, the close waits for the discard and its
 * snapshot leaves the invoice out. Without it, the close would snapshot the
 * invoice and the discard would commit just after, changing a closed quarter.
 *
 * Confirming a draft is not the dangerous case: a draft counts as pending, and
 * a quarter with pending invoices refuses to close at all.
 *
 * Validated by mutation (2026-10-08): with the `@Lock(PESSIMISTIC_WRITE)`
 * removed from `TrimestreRepository.bloquear`, the close no longer waited and
 * this test went red (the report no longer matched the snapshot); restored.
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
@Import(ConReconocedorFalso::class, CierreConcurrenteIT.ConSondaDeParada::class)
class CierreConcurrenteIT {

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

    /** Holds the next operation that reaches the probe until released. */
    class SondaDeParada : SondaConcurrencia {
        @Volatile var activa = false
        val llego = CountDownLatch(1)
        val soltar = CountDownLatch(1)
        override fun trasComprobar(facturaId: UUID) {
            if (!activa) return
            llego.countDown()
            soltar.await(30, TimeUnit.SECONDS)
        }
    }

    @TestConfiguration
    class ConSondaDeParada {
        @Bean @Primary fun sondaDeParada() = SondaDeParada()
    }

    @Autowired lateinit var trimestres: Trimestres
    @Autowired lateinit var revision: RevisionFacturas
    @Autowired lateinit var reportes: ReportesFacturacion
    @Autowired lateinit var subida: SubidaFacturas
    @Autowired lateinit var lecturas: FacturaLecturas
    @Autowired lateinit var empresa: EmpresaService
    @Autowired lateinit var falso: ReconocedorFalso
    @Autowired lateinit var sonda: SondaDeParada
    @Autowired lateinit var dataSource: DataSource

    private val e by lazy { EscenarioFacturas(subida, lecturas, falso, empresa, dataSource) }

    @Test
    fun `a close and a discard of the same quarter never leave it different from its snapshot`() {
        falso.reiniciar()
        e.configurarEmpresa()
        val ids = listOf("2025-02-10", "2025-03-05").map { fecha ->
            val id = e.borrador(ReconocedorFalso.propuesta(numero = "C-${UUID.randomUUID().toString().take(8)}", fechaEmision = fecha))
            revision.confirmar(id, lecturas.factura(id).version, e.admin)
            id
        }

        sonda.activa = true
        val hilos = Executors.newFixedThreadPool(2)
        val descarte = hilos.submit { revision.descartar(ids[0], lecturas.factura(ids[0]).version, e.admin) }
        assertTrue(sonda.llego.await(10, TimeUnit.SECONDS), "the discard reached the probe")
        sonda.activa = false

        val cierre = hilos.submit { trimestres.cerrar(2025, 1, e.admin) }
        Thread.sleep(700)
        val cierreEspero = !cierre.isDone

        sonda.soltar.countDown()
        descarte.get(20, TimeUnit.SECONDS)
        cierre.get(20, TimeUnit.SECONDS)
        hilos.shutdown()

        val foto = ObjectMapper().readTree(
            e.texto("SELECT totales::text FROM trimestre_eventos WHERE anio = 2025 AND trimestre = 1 AND accion = 'CIERRE'")
        )
        val reporte = reportes.calcular(Periodo.Trimestral(2025, 1))
        assertEquals(reporte.recibidas.facturas, foto["recibidas"]["facturas"].asInt(), "the closed quarter equals its snapshot")
        assertEquals(reporte.recibidas.total, BigDecimal(foto["recibidas"]["total"].asText()))
        assertEquals(1, reporte.recibidas.facturas, "the discarded invoice is out of both")
        assertTrue(cierreEspero, "the close waited for the discard holding the quarter lock")
    }
}
