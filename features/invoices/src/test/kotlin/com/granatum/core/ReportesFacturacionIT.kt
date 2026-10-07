package com.granatum.core

import com.granatum.core.domain.exception.PeriodoInvalidoException
import com.granatum.core.domain.model.Periodo
import com.granatum.core.service.ReportesFacturacion
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Random
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * US3 against the database (FR-018 to FR-021, SC-004): which invoices count in
 * which period, and that 100 invoices with cents add up exactly.
 *
 * Confirmed invoices are inserted directly: the upload path is tested
 * elsewhere, and here only the totals matter.
 */
@SpringBootTest(classes = [InvoicesTestApplication::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportesFacturacionIT {

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

    @Autowired lateinit var reportes: ReportesFacturacion
    @Autowired lateinit var dataSource: DataSource

    private val sumaManualEmitidas = mutableMapOf<String, BigDecimal>()

    private fun insertar(estado: String, tipo: String?, fecha: String, base: BigDecimal, tipoIva: String = "21.00") {
        val id = UUID.randomUUID()
        val cuota = (base * BigDecimal(tipoIva)).movePointLeft(2).setScale(2, RoundingMode.HALF_UP)
        val total = base + cuota
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO facturas (id, estado, tipo, emisor_nif, emisor_nif_normalizado, numero, fecha_emision,
                                      moneda, retenciones, total, documento_sha256, subida_por, subida_en,
                                      confirmada_por, confirmada_en, descartada_en, version)
                VALUES (?, ?, ?, 'A58818501', 'A58818501', ?, ?::date, 'EUR', 0, ?, ?, ?, now(),
                        CASE WHEN ? = 'CONFIRMADA' THEN ?::uuid END, CASE WHEN ? = 'CONFIRMADA' THEN now() END,
                        CASE WHEN ? = 'DESCARTADA' THEN now() END, 0)
                """.trimIndent()
            ).use { s ->
                val admin = UUID.randomUUID().toString()
                s.setObject(1, id); s.setString(2, estado); s.setString(3, tipo); s.setString(4, "R-$id")
                s.setString(5, fecha); s.setBigDecimal(6, total); s.setString(7, UUID.randomUUID().toString().replace("-", "").repeat(2))
                s.setObject(8, UUID.randomUUID()); s.setString(9, estado); s.setString(10, admin); s.setString(11, estado); s.setString(12, estado)
                s.executeUpdate()
            }
            c.prepareStatement("INSERT INTO factura_lineas_iva (id, factura_id, orden, tipo_iva, base, cuota, recargo) VALUES (?, ?, 0, ?::numeric, ?, ?, 0)").use { s ->
                s.setObject(1, UUID.randomUUID()); s.setObject(2, id); s.setString(3, tipoIva); s.setBigDecimal(4, base); s.setBigDecimal(5, cuota)
                s.executeUpdate()
            }
        }
        if (estado == "CONFIRMADA" && tipo == "EMITIDA") sumaManualEmitidas.merge(fecha.take(7), total, BigDecimal::add)
    }

    @BeforeAll
    fun sembrar() {
        // The quarter boundary: March 31st is Q1, April 1st is Q2.
        insertar("CONFIRMADA", "EMITIDA", "2025-03-31", BigDecimal("100.00"))
        insertar("CONFIRMADA", "EMITIDA", "2025-04-01", BigDecimal("200.00"))
        // Not confirmed: they do not count, but they are reported as pending.
        insertar("BORRADOR", null, "2025-04-15", BigDecimal("999.99"))
        insertar("PENDIENTE_RECONOCER", null, "2025-05-15", BigDecimal("999.99"))
        // Discarded: neither counts nor is pending.
        insertar("DESCARTADA", "EMITIDA", "2025-05-20", BigDecimal("777.77"))
        // 100 invoices with cents, in 2024.
        val r = Random(11)
        repeat(100) { i ->
            val base = BigDecimal(r.nextInt(1_000_000)).movePointLeft(2)
            insertar("CONFIRMADA", if (i % 3 == 0) "RECIBIDA" else "EMITIDA", "2024-${(1 + i % 12).toString().padStart(2, '0')}-1${i % 9}", base)
        }
    }

    @Test
    fun `only confirmed invoices count, and the quarter boundary is respected`() {
        val t1 = reportes.calcular(Periodo.Trimestral(2025, 1))
        val t2 = reportes.calcular(Periodo.Trimestral(2025, 2))
        assertEquals(1, t1.emitidas.facturas)
        assertEquals(BigDecimal("121.00"), t1.emitidas.total)
        assertEquals(1, t2.emitidas.facturas, "the draft, the pending and the discarded one do not count")
        assertEquals(BigDecimal("242.00"), t2.emitidas.total)
        assertEquals(2, t2.pendientes, "draft and pending, not the discarded one")
        assertEquals(0, t1.pendientes)
    }

    /** SC-004: exact to the cent against a manual sum. */
    @Test
    fun `a hundred invoices with cents add up exactly`() {
        val anual = reportes.calcular(Periodo.Anual(2024))
        assertEquals(100, anual.emitidas.facturas + anual.recibidas.facturas)
        assertEquals(sumaManualEmitidas.filterKeys { it.startsWith("2024") }.values.fold(BigDecimal.ZERO, BigDecimal::add), anual.emitidas.total)
        (1..12).forEach { mes ->
            val mensual = reportes.calcular(Periodo.Mensual(2024, mes))
            assertEquals(sumaManualEmitidas["2024-${mes.toString().padStart(2, '0')}"] ?: BigDecimal("0.00"), mensual.emitidas.total, "month $mes")
        }
    }

    @Test
    fun `an impossible period is refused`() {
        assertFailsWith<PeriodoInvalidoException> { reportes.periodo("MENSUAL", 2026, 13, null) }
        assertFailsWith<PeriodoInvalidoException> { reportes.periodo("TRIMESTRAL", 2026, null, 5) }
        assertFailsWith<PeriodoInvalidoException> { reportes.periodo("TRIMESTRAL", 2026, 3, 1) }
        assertFailsWith<PeriodoInvalidoException> { reportes.periodo("SEMANAL", 2026, null, null) }
        assertEquals(Periodo.Trimestral(2026, 3), reportes.periodo("TRIMESTRAL", 2026, null, 3))
    }
}
