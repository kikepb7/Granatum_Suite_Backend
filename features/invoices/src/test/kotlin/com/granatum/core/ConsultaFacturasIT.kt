package com.granatum.core

import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.model.TipoFactura
import com.granatum.core.domain.port.ResultadoReconocimiento
import com.granatum.core.service.ConsultaFacturas
import com.granatum.core.service.EmpresaService
import com.granatum.core.service.FacturaLecturas
import com.granatum.core.service.FicheroSubido
import com.granatum.core.service.FiltroFacturas
import com.granatum.core.service.RevisionFacturas
import com.granatum.core.service.SubidaFacturas
import com.granatum.core.service.ValoresFactura
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * US5: finding any invoice, and justifying any figure with its document and
 * its history (FR-024, FR-025, FR-005, FR-017).
 */
@SpringBootTest(classes = [InvoicesTestApplication::class])
@Import(ConReconocedorFalso::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsultaFacturasIT {

    companion object {
        // Started here: with PER_CLASS the context is built before the
        // @Testcontainers extension would start it.
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

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

    @Autowired lateinit var consulta: ConsultaFacturas
    @Autowired lateinit var revision: RevisionFacturas
    @Autowired lateinit var subida: SubidaFacturas
    @Autowired lateinit var lecturas: FacturaLecturas
    @Autowired lateinit var empresa: EmpresaService
    @Autowired lateinit var falso: ReconocedorFalso
    @Autowired lateinit var dataSource: DataSource

    private val e by lazy { EscenarioFacturas(subida, lecturas, falso, empresa, dataSource) }

    private lateinit var rosas: UUID
    private lateinit var tulipanes: UUID
    private lateinit var emitida: UUID
    private lateinit var sinFecha: UUID

    @BeforeAll
    fun sembrar() {
        falso.reiniciar()
        e.configurarEmpresa()
        rosas = e.borrador(ReconocedorFalso.propuesta(numero = "R-1", emisorNombre = "Rosales del Norte S.L.", fechaEmision = "2026-03-10"))
        revision.confirmar(rosas, lecturas.factura(rosas).version, e.admin)
        tulipanes = e.borrador(ReconocedorFalso.propuesta(numero = "T-1", emisorNombre = "Tulipanes 100%_Holanda", emisorNif = "A58818501", fechaEmision = "2026-04-10"))
        emitida = e.borrador(ReconocedorFalso.propuesta(numero = "E-1", emisorNombre = "Floristeria Granatum S.L.", emisorNif = "B12345674",
            destinatarioNombre = "Cliente Final", destinatarioNif = "12345678Z", fechaEmision = "2026-05-10"))
        revision.confirmar(emitida, lecturas.factura(emitida).version, e.admin)
        falso.guion = { ResultadoReconocimiento.Fallida("ERROR_API", ReconocedorFalso.MODELO) }
        sinFecha = subida.subir(listOf(FicheroSubido(null, Muestras.pdf())), e.admin).single().facturaId!!
        Thread.sleep(300)
    }

    private fun ids(f: FiltroFacturas) = consulta.listar(f).elementos.map { it.id }

    @Test
    fun `filters by date, party, type and state, combinable`() {
        assertEquals(listOf(rosas), ids(FiltroFacturas(desde = LocalDate.parse("2026-03-01"), hasta = LocalDate.parse("2026-03-31"))))
        assertEquals(listOf(tulipanes), ids(FiltroFacturas(parte = "tulipanes")), "name, case-insensitive")
        assertEquals(listOf(emitida), ids(FiltroFacturas(parte = "12345678z")), "recipient's tax id too")
        assertEquals(listOf(emitida), ids(FiltroFacturas(tipo = TipoFactura.EMITIDA)))
        assertEquals(setOf(tulipanes), ids(FiltroFacturas(estado = EstadoFactura.BORRADOR)).toSet())
        assertEquals(listOf(rosas), ids(FiltroFacturas(estado = EstadoFactura.CONFIRMADA, tipo = TipoFactura.RECIBIDA)))
    }

    /** `%` and `_` in a search are text, not wildcards. */
    @Test
    fun `a search with wildcard characters matches them literally`() {
        assertEquals(listOf(tulipanes), ids(FiltroFacturas(parte = "100%_")))
        // As a wildcard, `%` or `_` alone would match all four invoices.
        assertEquals(listOf(tulipanes), ids(FiltroFacturas(parte = "%")), "only the one with a literal %")
        assertEquals(listOf(tulipanes), ids(FiltroFacturas(parte = "_")), "only the one with a literal _")
    }

    @Test
    fun `newest first, invoices without a date before all, and paginated`() {
        val todas = ids(FiltroFacturas())
        assertEquals(listOf(sinFecha, emitida, tulipanes, rosas), todas)
        val pagina = consulta.listar(FiltroFacturas(pagina = 1, tamano = 2))
        assertEquals(listOf(tulipanes, rosas), pagina.elementos.map { it.id })
        assertEquals(4, pagina.total)
    }

    @Test
    fun `the summary carries the warnings count`() {
        val resumen = consulta.listar(FiltroFacturas(parte = "tulipanes")).elementos.single()
        assertEquals(0, resumen.numeroAvisos)
        val pendiente = consulta.listar(FiltroFacturas(estado = EstadoFactura.PENDIENTE_RECONOCER)).elementos.single()
        assertTrue(pendiente.numeroAvisos > 0, "a pending invoice lacks every required field")
    }

    /** FR-005, FR-017: what was proposed, and every change with its previous values. */
    @Test
    fun `the history has the recognitions and the changes`() {
        revision.corregir(rosas, ValoresFactura.de(lecturas.factura(rosas)).copy(concepto = "Rosas blancas"), lecturas.factura(rosas).version, e.admin)
        val h = consulta.historial(rosas)
        assertEquals("RECONOCIDA", h.reconocimientos.single().resultado)
        assertEquals("R-1", (h.reconocimientos.single().propuesta as Map<*, *>)["numero"])
        assertEquals("CORRECCION", h.cambios.single().accion)
        assertEquals("Rosas rojas", (h.cambios.single().valoresAnteriores as Map<*, *>)["concepto"])
    }
}
