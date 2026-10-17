package com.granatum.core

import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.model.InvoiceType
import com.granatum.core.domain.port.RecognitionResult
import com.granatum.core.service.InvoiceQueries
import com.granatum.core.service.CompanyService
import com.granatum.core.service.InvoiceReader
import com.granatum.core.service.UploadedInvoiceFile
import com.granatum.core.service.InvoiceFilter
import com.granatum.core.service.InvoiceReview
import com.granatum.core.service.InvoiceUploads
import com.granatum.core.service.InvoiceValues
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
@Import(WithFakeInvoiceRecognizer::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvoiceQueriesIT {

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

    @Autowired lateinit var queries: InvoiceQueries
    @Autowired lateinit var review: InvoiceReview
    @Autowired lateinit var uploads: InvoiceUploads
    @Autowired lateinit var reader: InvoiceReader
    @Autowired lateinit var company: CompanyService
    @Autowired lateinit var fake: FakeInvoiceRecognizer
    @Autowired lateinit var dataSource: DataSource

    private val e by lazy { InvoiceScenario(uploads, reader, fake, company, dataSource) }

    private lateinit var roses: UUID
    private lateinit var tulips: UUID
    private lateinit var issued: UUID
    private lateinit var undated: UUID

    @BeforeAll
    fun seed() {
        fake.reset()
        e.configureCompany()
        roses = e.draft(FakeInvoiceRecognizer.proposal(number = "R-1", issuerName = "Rosales del Norte S.L.", issueDate = "2026-03-10"))
        review.confirm(roses, reader.invoice(roses).version, e.admin)
        tulips = e.draft(FakeInvoiceRecognizer.proposal(number = "T-1", issuerName = "Tulipanes 100%_Holanda", issuerNif = "A58818501", issueDate = "2026-04-10"))
        issued = e.draft(FakeInvoiceRecognizer.proposal(number = "E-1", issuerName = "Floristeria Granatum S.L.", issuerNif = "B12345674",
            recipientName = "Cliente Final", recipientNif = "12345678Z", issueDate = "2026-05-10"))
        review.confirm(issued, reader.invoice(issued).version, e.admin)
        fake.script = { RecognitionResult.Failed("API_ERROR", FakeInvoiceRecognizer.MODEL) }
        undated = uploads.upload(listOf(UploadedInvoiceFile(null, InvoiceSamples.pdf())), e.admin).single().invoiceId!!
        Thread.sleep(300)
    }

    private fun ids(f: InvoiceFilter) = queries.list(f).items.map { it.id }

    @Test
    fun `filters by date, party, type and state, combinable`() {
        assertEquals(listOf(roses), ids(InvoiceFilter(from = LocalDate.parse("2026-03-01"), to = LocalDate.parse("2026-03-31"))))
        assertEquals(listOf(tulips), ids(InvoiceFilter(party = "tulipanes")), "name, case-insensitive")
        assertEquals(listOf(issued), ids(InvoiceFilter(party = "12345678z")), "recipient's tax id too")
        assertEquals(listOf(issued), ids(InvoiceFilter(type = InvoiceType.ISSUED)))
        assertEquals(setOf(tulips), ids(InvoiceFilter(status = InvoiceStatus.DRAFT)).toSet())
        assertEquals(listOf(roses), ids(InvoiceFilter(status = InvoiceStatus.CONFIRMED, type = InvoiceType.RECEIVED)))
    }

    /** `%` and `_` in a search are text, not wildcards. */
    @Test
    fun `a search with wildcard characters matches them literally`() {
        assertEquals(listOf(tulips), ids(InvoiceFilter(party = "100%_")))
        // As a wildcard, `%` or `_` alone would match all four invoices.
        assertEquals(listOf(tulips), ids(InvoiceFilter(party = "%")), "only the one with a literal %")
        assertEquals(listOf(tulips), ids(InvoiceFilter(party = "_")), "only the one with a literal _")
    }

    @Test
    fun `newest first, invoices without a date before all, and paginated`() {
        val all = ids(InvoiceFilter())
        assertEquals(listOf(undated, issued, tulips, roses), all)
        val page = queries.list(InvoiceFilter(page = 1, size = 2))
        assertEquals(listOf(tulips, roses), page.items.map { it.id })
        assertEquals(4, page.total)
    }

    @Test
    fun `the summary carries the warnings count`() {
        val summary = queries.list(InvoiceFilter(party = "tulipanes")).items.single()
        assertEquals(0, summary.noticeCount)
        val pendingOne = queries.list(InvoiceFilter(status = InvoiceStatus.PENDING_RECOGNITION)).items.single()
        assertTrue(pendingOne.noticeCount > 0, "a pending invoice lacks every required field")
    }

    /** FR-005, FR-017: what was proposed, and every change with its previous values. */
    @Test
    fun `the history has the recognitions and the changes`() {
        review.correct(roses, InvoiceValues.from(reader.invoice(roses)).copy(concept = "Rosas blancas"), reader.invoice(roses).version, e.admin)
        val h = queries.history(roses)
        assertEquals("RECOGNIZED", h.recognitions.single().result)
        assertEquals("R-1", (h.recognitions.single().proposal as Map<*, *>)["number"])
        assertEquals("CORRECTION", h.changes.single().action)
        assertEquals("Rosas rojas", (h.changes.single().previousValues as Map<*, *>)["concept"])
    }
}
