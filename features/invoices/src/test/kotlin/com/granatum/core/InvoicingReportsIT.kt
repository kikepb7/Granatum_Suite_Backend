package com.granatum.core

import com.granatum.core.domain.exception.InvalidReportPeriodException
import com.granatum.core.domain.model.ReportPeriod
import com.granatum.core.service.InvoicingReports
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
class InvoicingReportsIT {

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

    @Autowired lateinit var reports: InvoicingReports
    @Autowired lateinit var dataSource: DataSource

    private val manualIssuedSums = mutableMapOf<String, BigDecimal>()

    private fun insert(status: String, type: String?, date: String, base: BigDecimal, vatRate: String = "21.00") {
        val id = UUID.randomUUID()
        val vatAmount = (base * BigDecimal(vatRate)).movePointLeft(2).setScale(2, RoundingMode.HALF_UP)
        val total = base + vatAmount
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO invoices (id, status, type, issuer_nif, issuer_normalized_nif, number, issue_date,
                                      currency, withholdings, total, document_sha256, uploaded_by, uploaded_at,
                                      confirmed_by, confirmed_at, discarded_at, version)
                VALUES (?, ?, ?, 'A58818501', 'A58818501', ?, ?::date, 'EUR', 0, ?, ?, ?, now(),
                        CASE WHEN ? = 'CONFIRMED' THEN ?::uuid END, CASE WHEN ? = 'CONFIRMED' THEN now() END,
                        CASE WHEN ? = 'DISCARDED' THEN now() END, 0)
                """.trimIndent()
            ).use { s ->
                val admin = UUID.randomUUID().toString()
                s.setObject(1, id); s.setString(2, status); s.setString(3, type); s.setString(4, "R-$id")
                s.setString(5, date); s.setBigDecimal(6, total); s.setString(7, UUID.randomUUID().toString().replace("-", "").repeat(2))
                s.setObject(8, UUID.randomUUID()); s.setString(9, status); s.setString(10, admin); s.setString(11, status); s.setString(12, status)
                s.executeUpdate()
            }
            c.prepareStatement("INSERT INTO invoice_vat_lines (id, invoice_id, sort_order, vat_rate, base, vat_amount, surcharge) VALUES (?, ?, 0, ?::numeric, ?, ?, 0)").use { s ->
                s.setObject(1, UUID.randomUUID()); s.setObject(2, id); s.setString(3, vatRate); s.setBigDecimal(4, base); s.setBigDecimal(5, vatAmount)
                s.executeUpdate()
            }
        }
        if (status == "CONFIRMED" && type == "ISSUED") manualIssuedSums.merge(date.take(7), total, BigDecimal::add)
    }

    @BeforeAll
    fun seed() {
        // The quarter boundary: March 31st is Q1, April 1st is Q2.
        insert("CONFIRMED", "ISSUED", "2025-03-31", BigDecimal("100.00"))
        insert("CONFIRMED", "ISSUED", "2025-04-01", BigDecimal("200.00"))
        // Not confirmed: they do not count, but they are reported as pending.
        insert("DRAFT", null, "2025-04-15", BigDecimal("999.99"))
        insert("PENDING_RECOGNITION", null, "2025-05-15", BigDecimal("999.99"))
        // Discarded: neither counts nor is pending.
        insert("DISCARDED", "ISSUED", "2025-05-20", BigDecimal("777.77"))
        // 100 invoices with cents, in 2024.
        val r = Random(11)
        repeat(100) { i ->
            val base = BigDecimal(r.nextInt(1_000_000)).movePointLeft(2)
            insert("CONFIRMED", if (i % 3 == 0) "RECEIVED" else "ISSUED", "2024-${(1 + i % 12).toString().padStart(2, '0')}-1${i % 9}", base)
        }
    }

    @Test
    fun `only confirmed invoices count, and the quarter boundary is respected`() {
        val t1 = reports.calculate(ReportPeriod.Quarterly(2025, 1))
        val t2 = reports.calculate(ReportPeriod.Quarterly(2025, 2))
        assertEquals(1, t1.issued.invoices)
        assertEquals(BigDecimal("121.00"), t1.issued.total)
        assertEquals(1, t2.issued.invoices, "the draft, the pending and the discarded one do not count")
        assertEquals(BigDecimal("242.00"), t2.issued.total)
        assertEquals(2, t2.pending, "draft and pending, not the discarded one")
        assertEquals(0, t1.pending)
    }

    /** SC-004: exact to the cent against a manual sum. */
    @Test
    fun `a hundred invoices with cents add up exactly`() {
        val yearly = reports.calculate(ReportPeriod.Yearly(2024))
        assertEquals(100, yearly.issued.invoices + yearly.received.invoices)
        assertEquals(manualIssuedSums.filterKeys { it.startsWith("2024") }.values.fold(BigDecimal.ZERO, BigDecimal::add), yearly.issued.total)
        (1..12).forEach { month ->
            val monthly = reports.calculate(ReportPeriod.Monthly(2024, month))
            assertEquals(manualIssuedSums["2024-${month.toString().padStart(2, '0')}"] ?: BigDecimal("0.00"), monthly.issued.total, "month $month")
        }
    }

    @Test
    fun `an impossible period is refused`() {
        assertFailsWith<InvalidReportPeriodException> { reports.period("MONTHLY", 2026, 13, null) }
        assertFailsWith<InvalidReportPeriodException> { reports.period("QUARTERLY", 2026, null, 5) }
        assertFailsWith<InvalidReportPeriodException> { reports.period("QUARTERLY", 2026, 3, 1) }
        assertFailsWith<InvalidReportPeriodException> { reports.period("WEEKLY", 2026, null, null) }
        assertEquals(ReportPeriod.Quarterly(2026, 3), reports.period("QUARTERLY", 2026, null, 3))
    }
}
