package com.granatum.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.granatum.core.domain.exception.QuarterOpenException
import com.granatum.core.domain.exception.QuarterClosedException
import com.granatum.core.domain.exception.QuarterHasPendingException
import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.model.ReportPeriod
import com.granatum.core.service.CompanyService
import com.granatum.core.service.InvoiceReader
import com.granatum.core.service.InvoicingReports
import com.granatum.core.service.InvoiceReview
import com.granatum.core.service.InvoiceUploads
import com.granatum.core.service.Quarters
import com.granatum.core.service.InvoiceValues
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
@Import(WithFakeInvoiceRecognizer::class)
class QuartersIT {

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

    @Autowired lateinit var quarters: Quarters
    @Autowired lateinit var review: InvoiceReview
    @Autowired lateinit var reports: InvoicingReports
    @Autowired lateinit var uploads: InvoiceUploads
    @Autowired lateinit var reader: InvoiceReader
    @Autowired lateinit var company: CompanyService
    @Autowired lateinit var fake: FakeInvoiceRecognizer
    @Autowired lateinit var dataSource: DataSource

    private val e by lazy { InvoiceScenario(uploads, reader, fake, company, dataSource) }

    @BeforeEach
    fun setUp() {
        fake.reset()
        e.configureCompany()
    }

    private fun draftDated(date: String) =
        e.draft(FakeInvoiceRecognizer.proposal(number = "T-${UUID.randomUUID().toString().take(8)}", issueDate = date))

    private fun confirmedDated(date: String): UUID {
        val id = draftDated(date)
        review.confirm(id, reader.invoice(id).version, e.admin)
        return id
    }

    private fun closingTotals(year: Int, t: Int): String =
        e.text("SELECT totals::text FROM quarter_events WHERE year = ? AND quarter = ? AND action = 'CLOSE' ORDER BY occurred_at DESC LIMIT 1", year.toShort(), t.toShort())!!

    @Test
    fun `a quarter with drafts cannot be closed`() {
        draftDated("2022-02-10")
        assertFailsWith<QuarterHasPendingException> { quarters.close(2022, 1, e.admin) }
    }

    /** SC-009: the snapshot taken at close time is the report, and stays so. */
    @Test
    fun `closing stores a snapshot that equals the report`() {
        confirmedDated("2021-05-10")
        confirmedDated("2021-06-20")
        quarters.close(2021, 2, e.admin)

        val report = reports.calculate(ReportPeriod.Quarterly(2021, 2))
        val snapshot = ObjectMapper().readTree(closingTotals(2021, 2))
        assertEquals(report.received.total, BigDecimal(snapshot["received"]["total"].asText()))
        assertEquals(report.received.invoices, snapshot["received"]["invoices"].asInt())
        assertEquals(2, report.received.invoices)
    }

    /** FR-031 */
    @Test
    fun `nothing of a closed quarter can be confirmed, corrected or discarded`() {
        val confirmed = confirmedDated("2020-08-10")
        quarters.close(2020, 3, e.admin)

        assertFailsWith<QuarterClosedException> {
            review.correct(confirmed, InvoiceValues.from(reader.invoice(confirmed)).copy(concept = "Cambio"), reader.invoice(confirmed).version, e.admin)
        }
        assertFailsWith<QuarterClosedException> { review.discard(confirmed, reader.invoice(confirmed).version, e.admin) }

        // An invoice arriving late still enters, as a draft (research.md D-016)...
        val late = draftDated("2020-09-01")
        assertEquals(InvoiceStatus.DRAFT, reader.status(late))
        // ...but cannot be confirmed into the closed quarter.
        assertFailsWith<QuarterClosedException> { review.confirm(late, reader.invoice(late).version, e.admin) }
    }

    /** FR-032 */
    @Test
    fun `reopening needs the quarter closed, and every close and reopening is recorded in order`() {
        confirmedDated("2019-11-10")
        assertFailsWith<QuarterOpenException> { quarters.reopen(2019, 4, "Rectificacion de una factura", e.admin) }

        quarters.close(2019, 4, e.admin)
        assertFailsWith<QuarterClosedException> { quarters.close(2019, 4, e.admin) }
        quarters.reopen(2019, 4, "Rectificacion de una factura", e.admin)
        quarters.close(2019, 4, e.admin)

        val q4 = quarters.status(2019).single { it.quarter == 4 }
        assertTrue(q4.closed)
        assertEquals(listOf("CLOSE", "REOPEN", "CLOSE"), q4.events.map { it.action })
        assertEquals("Rectificacion de una factura", q4.events[1].reason)
    }

    /** FR-033, finding C5: a month and the year that contain a closed quarter say so. */
    @Test
    fun `monthly and annual reports show the closed quarter`() {
        confirmedDated("2018-01-15")
        quarters.close(2018, 1, e.admin)
        assertEquals(listOf(1), reports.calculate(ReportPeriod.Monthly(2018, 2)).closedQuarters.map { it.quarter })
        assertEquals(listOf(1), reports.calculate(ReportPeriod.Yearly(2018)).closedQuarters.map { it.quarter })
        assertEquals(emptyList(), reports.calculate(ReportPeriod.Monthly(2018, 4)).closedQuarters)
    }
}
