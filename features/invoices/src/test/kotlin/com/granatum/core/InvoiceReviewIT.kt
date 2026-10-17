package com.granatum.core

import com.granatum.core.domain.exception.CompanyNotConfiguredException
import com.granatum.core.domain.exception.InvoiceStatusNotAllowedException
import com.granatum.core.domain.exception.InconsistentInvoiceException
import com.granatum.core.domain.exception.StaleInvoiceVersionException
import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.model.VatLine
import com.granatum.core.domain.model.InvoiceType
import com.granatum.core.domain.port.InvoiceDocumentStore
import com.granatum.core.domain.port.RecognitionResult
import com.granatum.core.service.CompanyService
import com.granatum.core.service.InvoiceReader
import com.granatum.core.service.UploadedInvoiceFile
import com.granatum.core.service.InvoiceReview
import com.granatum.core.service.InvoiceUploads
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * US2: reviewing, correcting, confirming and discarding (FR-009 to FR-017).
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
@Import(WithFakeInvoiceRecognizer::class)
class InvoiceReviewIT {

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

    @Autowired lateinit var review: InvoiceReview
    @Autowired lateinit var uploads: InvoiceUploads
    @Autowired lateinit var reader: InvoiceReader
    @Autowired lateinit var company: CompanyService
    @Autowired lateinit var store: InvoiceDocumentStore
    @Autowired lateinit var fake: FakeInvoiceRecognizer
    @Autowired lateinit var dataSource: DataSource

    private val e by lazy { InvoiceScenario(uploads, reader, fake, company, dataSource) }

    @BeforeEach
    fun setUp() {
        fake.reset()
        e.configureCompany()
    }

    private fun values(id: UUID) = InvoiceValues.from(reader.invoice(id))
    private fun d(v: String) = BigDecimal(v)

    @Test
    fun `a draft is corrected and stays a draft`() {
        val id = e.draft()
        val f = review.correct(id, values(id).copy(concept = "Tulipanes"), reader.invoice(id).version, e.admin)
        assertEquals("Tulipanes", f.concept)
        assertEquals(InvoiceStatus.DRAFT, f.status)
    }

    /** FR-006 (moved here from ManualModeIT): a pending invoice filled in by hand becomes a draft. */
    @Test
    fun `a pending invoice filled in by hand becomes a draft`() {
        fake.script = { RecognitionResult.Failed("API_ERROR", FakeInvoiceRecognizer.MODEL) }
        val id = uploads.upload(listOf(UploadedInvoiceFile(null, InvoiceSamples.pdf())), e.admin).single().invoiceId!!
        Thread.sleep(300)
        assertEquals(InvoiceStatus.PENDING_RECOGNITION, reader.status(id))

        val byHand = InvoiceValues(
            type = null, issuerName = "Proveedor", issuerNif = "A58818501", recipientName = null,
            recipientNif = "B12345674", number = "M-${UUID.randomUUID().toString().take(6)}",
            issueDate = java.time.LocalDate.parse("2026-10-03"), concept = "A mano", currency = "EUR",
            vatLines = listOf(VatLine(d("21.00"), d("10.00"), d("2.10"))), withholdings = d("0.00"),
            total = d("12.10"), corrective = false
        )
        val f = review.correct(id, byHand, reader.invoice(id).version, e.admin)
        assertEquals(InvoiceStatus.DRAFT, f.status)
        assertEquals(InvoiceStatus.CONFIRMED, review.confirm(id, f.version, e.admin).status)
    }

    /** FR-011: blocking warnings stop the confirmation. */
    @Test
    fun `an invoice that does not add up cannot be confirmed`() {
        val id = e.draft(FakeInvoiceRecognizer.proposal(number = "N-${UUID.randomUUID().toString().take(6)}", total = "175.00"))
        assertFailsWith<InconsistentInvoiceException> { review.confirm(id, reader.invoice(id).version, e.admin) }
        assertEquals(InvoiceStatus.DRAFT, reader.status(id))
    }

    @Test
    fun `a correct one is confirmed, its type deduced from the company's tax id`() {
        val id = e.draft()
        val f = review.confirm(id, reader.invoice(id).version, e.admin)
        assertEquals(InvoiceStatus.CONFIRMED, f.status)
        assertEquals(InvoiceType.RECEIVED, f.type, "the company is the recipient")
        assertEquals(e.admin.toString(), e.text("SELECT confirmed_by FROM invoices WHERE id = ?", id))
    }

    @Test
    fun `without the company's data nothing can be confirmed`() {
        val id = e.draft()
        e.execute("DELETE FROM companies")
        try {
            assertFailsWith<CompanyNotConfiguredException> { review.confirm(id, reader.invoice(id).version, e.admin) }
        } finally {
            e.configureCompany()
        }
    }

    @Test
    fun `a stale version is refused`() {
        val id = e.draft()
        val stale = reader.invoice(id).version
        review.correct(id, values(id).copy(concept = "Primero"), stale, e.admin)
        assertFailsWith<StaleInvoiceVersionException> {
            review.correct(id, values(id).copy(concept = "Segundo"), stale, e.admin)
        }
    }

    /** FR-015, FR-017 (finding C4). */
    @Test
    fun `discarding keeps the invoice and its original, and records who and when`() {
        val id = e.draft()
        review.discard(id, reader.invoice(id).version, e.admin)
        assertEquals(InvoiceStatus.DISCARDED, reader.status(id))
        assertTrue(store.read(id).content.isNotEmpty())
        assertEquals(e.admin.toString(), e.text("SELECT discarded_by FROM invoices WHERE id = ?", id))
        assertNotNull(e.text("SELECT discarded_at FROM invoices WHERE id = ?", id))
        assertFailsWith<InvoiceStatusNotAllowedException> { review.confirm(id, reader.invoice(id).version, e.admin) }
    }

    /** FR-016, FR-017: a confirmed invoice keeps its history, and never becomes incomplete. */
    @Test
    fun `correcting a confirmed invoice records the previous values and refuses an incomplete result`() {
        val id = e.draft()
        val confirmed = review.confirm(id, reader.invoice(id).version, e.admin)

        assertFailsWith<InconsistentInvoiceException> {
            review.correct(id, values(id).copy(number = null), confirmed.version, e.admin)
        }
        val corrected = review.correct(id, values(id).copy(concept = "Rosas blancas"), confirmed.version, e.admin)

        assertEquals("Rosas blancas", corrected.concept)
        assertEquals(InvoiceStatus.CONFIRMED, corrected.status)
        assertEquals("CORRECTION", e.text("SELECT action FROM invoice_changes WHERE invoice_id = ?", id))
        val previous = e.text("SELECT previous_values::text FROM invoice_changes WHERE invoice_id = ?", id)!!
        assertTrue(previous.contains("Rosas rojas") && previous.contains("176.00") && previous.contains("2026-10-02"), previous)
    }

    /** FR-008 (finding C3). */
    @Test
    fun `reclassifying a confirmed invoice is recorded as such`() {
        val id = e.draft()
        val confirmed = review.confirm(id, reader.invoice(id).version, e.admin)
        review.correct(id, values(id).copy(type = InvoiceType.ISSUED), confirmed.version, e.admin)
        assertEquals("RECLASSIFICATION", e.text("SELECT action FROM invoice_changes WHERE invoice_id = ?", id))
    }

    @Test
    fun `discarding a confirmed invoice is recorded`() {
        val id = e.draft()
        val confirmed = review.confirm(id, reader.invoice(id).version, e.admin)
        review.discard(id, confirmed.version, e.admin)
        assertEquals("DISCARD", e.text("SELECT action FROM invoice_changes WHERE invoice_id = ?", id))
    }

    /** FR-005: the proposal stays as it was, whatever the reviewer changes. */
    @Test
    fun `the original proposal survives a correction`() {
        val number = "P-${UUID.randomUUID().toString().take(6)}"
        val id = e.draft(FakeInvoiceRecognizer.proposal(number = number))
        review.correct(id, values(id).copy(number = "OTRO-1"), reader.invoice(id).version, e.admin)
        assertTrue(e.text("SELECT proposal::text FROM invoice_recognitions WHERE invoice_id = ?", id)!!.contains(number))
    }
}
