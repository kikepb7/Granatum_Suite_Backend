package com.granatum.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.granatum.core.domain.exception.TrimestreAbiertoException
import com.granatum.core.domain.exception.TrimestreCerradoException
import com.granatum.core.domain.exception.TrimestreConPendientesException
import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.model.Periodo
import com.granatum.core.service.EmpresaService
import com.granatum.core.service.FacturaLecturas
import com.granatum.core.service.ReportesFacturacion
import com.granatum.core.service.RevisionFacturas
import com.granatum.core.service.SubidaFacturas
import com.granatum.core.service.Trimestres
import com.granatum.core.service.ValoresFactura
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
import java.math.BigDecimal
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * US6: a declared quarter does not change until someone reopens it, with a
 * reason (FR-030 to FR-033, SC-009). Each test uses its own year, so they do
 * not close each other's quarters.
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
@Import(ConReconocedorFalso::class)
class TrimestresIT {

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

    @Autowired lateinit var trimestres: Trimestres
    @Autowired lateinit var revision: RevisionFacturas
    @Autowired lateinit var reportes: ReportesFacturacion
    @Autowired lateinit var subida: SubidaFacturas
    @Autowired lateinit var lecturas: FacturaLecturas
    @Autowired lateinit var empresa: EmpresaService
    @Autowired lateinit var falso: ReconocedorFalso
    @Autowired lateinit var dataSource: DataSource

    private val e by lazy { EscenarioFacturas(subida, lecturas, falso, empresa, dataSource) }

    @BeforeEach
    fun preparar() {
        falso.reiniciar()
        e.configurarEmpresa()
    }

    private fun borradorDel(fecha: String) =
        e.borrador(ReconocedorFalso.propuesta(numero = "T-${UUID.randomUUID().toString().take(8)}", fechaEmision = fecha))

    private fun confirmadaDel(fecha: String): UUID {
        val id = borradorDel(fecha)
        revision.confirmar(id, lecturas.factura(id).version, e.admin)
        return id
    }

    private fun totalesDelCierre(anio: Int, t: Int): String =
        e.texto("SELECT totales::text FROM trimestre_eventos WHERE anio = ? AND trimestre = ? AND accion = 'CIERRE' ORDER BY ocurrido_en DESC LIMIT 1", anio.toShort(), t.toShort())!!

    @Test
    fun `a quarter with drafts cannot be closed`() {
        borradorDel("2022-02-10")
        assertFailsWith<TrimestreConPendientesException> { trimestres.cerrar(2022, 1, e.admin) }
    }

    /** SC-009: the snapshot taken at close time is the report, and stays so. */
    @Test
    fun `closing stores a snapshot that equals the report`() {
        confirmadaDel("2021-05-10")
        confirmadaDel("2021-06-20")
        trimestres.cerrar(2021, 2, e.admin)

        val reporte = reportes.calcular(Periodo.Trimestral(2021, 2))
        val foto = ObjectMapper().readTree(totalesDelCierre(2021, 2))
        assertEquals(reporte.recibidas.total, BigDecimal(foto["recibidas"]["total"].asText()))
        assertEquals(reporte.recibidas.facturas, foto["recibidas"]["facturas"].asInt())
        assertEquals(2, reporte.recibidas.facturas)
    }

    /** FR-031 */
    @Test
    fun `nothing of a closed quarter can be confirmed, corrected or discarded`() {
        val confirmada = confirmadaDel("2020-08-10")
        trimestres.cerrar(2020, 3, e.admin)

        assertFailsWith<TrimestreCerradoException> {
            revision.corregir(confirmada, ValoresFactura.de(lecturas.factura(confirmada)).copy(concepto = "Cambio"), lecturas.factura(confirmada).version, e.admin)
        }
        assertFailsWith<TrimestreCerradoException> { revision.descartar(confirmada, lecturas.factura(confirmada).version, e.admin) }

        // An invoice arriving late still enters, as a draft (research.md D-016)...
        val tardia = borradorDel("2020-09-01")
        assertEquals(EstadoFactura.BORRADOR, lecturas.estado(tardia))
        // ...but cannot be confirmed into the closed quarter.
        assertFailsWith<TrimestreCerradoException> { revision.confirmar(tardia, lecturas.factura(tardia).version, e.admin) }
    }

    /** FR-032 */
    @Test
    fun `reopening needs the quarter closed, and every close and reopening is recorded in order`() {
        confirmadaDel("2019-11-10")
        assertFailsWith<TrimestreAbiertoException> { trimestres.reabrir(2019, 4, "Rectificacion de una factura", e.admin) }

        trimestres.cerrar(2019, 4, e.admin)
        assertFailsWith<TrimestreCerradoException> { trimestres.cerrar(2019, 4, e.admin) }
        trimestres.reabrir(2019, 4, "Rectificacion de una factura", e.admin)
        trimestres.cerrar(2019, 4, e.admin)

        val q4 = trimestres.estado(2019).single { it.trimestre == 4 }
        assertTrue(q4.cerrado)
        assertEquals(listOf("CIERRE", "REAPERTURA", "CIERRE"), q4.eventos.map { it.accion })
        assertEquals("Rectificacion de una factura", q4.eventos[1].motivo)
    }

    /** FR-033, finding C5: a month and the year that contain a closed quarter say so. */
    @Test
    fun `monthly and annual reports show the closed quarter`() {
        confirmadaDel("2018-01-15")
        trimestres.cerrar(2018, 1, e.admin)
        assertEquals(listOf(1), reportes.calcular(Periodo.Mensual(2018, 2)).trimestresCerrados.map { it.trimestre })
        assertEquals(listOf(1), reportes.calcular(Periodo.Anual(2018)).trimestresCerrados.map { it.trimestre })
        assertEquals(emptyList(), reportes.calcular(Periodo.Mensual(2018, 4)).trimestresCerrados)
    }
}
