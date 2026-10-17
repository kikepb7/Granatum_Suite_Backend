package com.granatum.core

import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.port.InvoiceDocumentStore
import com.granatum.core.domain.port.RecognitionResult
import com.granatum.core.service.InvoiceReader
import com.granatum.core.service.UploadedInvoiceFile
import com.granatum.core.service.InvoiceUploads
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * US1: uploading invoices and getting them recognised (FR-001 to FR-006,
 * FR-014, SC-006), with the double in place of Claude.
 *
 * ## The rule under test above all: never call Claude inside a transaction
 *
 * A call can take tens of seconds; holding a database connection meanwhile is
 * how features 002 and 003 exhausted the pool (research.md D-005). The double
 * records whether a transaction was active when it was called.
 *
 * Validated by mutation (2026-10-08): wrapping the call to the recogniser in
 * `RecognitionQueue` with `transactions.execute { … }` turned
 * `the recogniser is never called inside a transaction` red; restored.
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
@Import(WithFakeInvoiceRecognizer::class)
class InvoiceUploadsIT {

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

    @Autowired lateinit var uploads: InvoiceUploads
    @Autowired lateinit var fake: FakeInvoiceRecognizer
    @Autowired lateinit var store: InvoiceDocumentStore
    @Autowired lateinit var reader: InvoiceReader
    @Autowired lateinit var dataSource: DataSource

    private val admin = UUID.randomUUID()

    @BeforeEach
    fun reset() = fake.reset()

    private fun upload(vararg contents: ByteArray) =
        uploads.upload(contents.map { UploadedInvoiceFile("factura.pdf", it) }, admin)

    /** Recognition runs in the background: wait until the invoice leaves the pending state. */
    private fun waitForStatus(id: UUID, status: InvoiceStatus) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < deadline) {
            if (reader.status(id) == status) return
            Thread.sleep(50)
        }
        throw AssertionError("la factura no llego a $status; esta en ${reader.status(id)}")
    }

    private fun waitForAttempts(id: UUID, n: Int) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < deadline && countRows("invoice_recognitions", id) < n) Thread.sleep(50)
    }

    private fun countRows(table: String, invoiceId: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM $table WHERE invoice_id = ?").use { s ->
            s.setObject(1, invoiceId); s.executeQuery().use { r -> r.next(); r.getInt(1) }
        }
    }

    @Test
    fun `several files give one result each, in order and without names`() {
        val r = upload(InvoiceSamples.pdf(), InvoiceSamples.png(), InvoiceSamples.text(), ByteArray(0))
        assertEquals(listOf(1, 2, 3, 4), r.map { it.file })
        assertEquals(listOf("ACCEPTED", "ACCEPTED", "UNSUPPORTED_FORMAT", "EMPTY"), r.map { it.result })
        assertTrue(r[0].invoiceId != null && r[1].invoiceId != null && r[2].invoiceId == null)
    }

    /** Finding I1: the 10 MB per file is the service's, so one big file does not sink the rest. */
    @Test
    fun `a file over 10 MB is refused alone`() {
        val r = upload(InvoiceSamples.pdf(), InvoiceSamples.tooLargePdf(), InvoiceSamples.png())
        assertEquals(listOf("ACCEPTED", "TOO_LARGE", "ACCEPTED"), r.map { it.result })
    }

    @Test
    fun `an unreadable PDF is refused at upload`() {
        assertEquals("UNREADABLE_PDF", upload("%PDF-1.7 roto".toByteArray()).single().result)
    }

    @Test
    fun `the same file twice is a duplicate pointing at the first`() {
        val pdf = InvoiceSamples.pdf()
        val first = upload(pdf).single()
        val second = upload(pdf).single()
        assertEquals("DUPLICATE", second.result)
        assertEquals(first.invoiceId, second.invoiceId)
    }

    /** The database's unique index, not the service's check, is what holds under a race (D-015). */
    @Test
    fun `two simultaneous uploads of the same file leave a single invoice`() {
        val pdf = InvoiceSamples.pdf()
        val output = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        val futures = (1..2).map { threads.submit<String> { output.await(); upload(pdf).single().result } }
        output.countDown()
        val results = futures.map { it.get(20, TimeUnit.SECONDS) }.sorted()
        threads.shutdown()

        assertEquals(listOf("ACCEPTED", "DUPLICATE"), results)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(pdf).joinToString("") { "%02x".format(it) }
        val rows = dataSource.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM invoices WHERE document_sha256 = ?").use { s ->
                s.setString(1, fingerprint); s.executeQuery().use { r -> r.next(); r.getInt(1) }
            }
        }
        assertEquals(1, rows)
    }

    @Test
    fun `the stored original is identical to the upload`() {
        val png = InvoiceSamples.png()
        val id = upload(png).single().invoiceId!!
        val stored = store.read(id)
        assertContentEquals(png, stored.content)
        assertEquals("image/png", stored.mediaType)
    }

    @Test
    fun `a recognised invoice becomes a draft with what was proposed, two VAT lines included`() {
        val id = upload(InvoiceSamples.pdf()).single().invoiceId!!
        waitForStatus(id, InvoiceStatus.DRAFT)

        val f = reader.invoice(id)
        assertEquals("2026-0815", f.number)
        assertEquals("A58818501", f.issuer?.nif)
        assertEquals(BigDecimal("176.00"), f.total)
        assertEquals(2, f.vatLines.size)
        assertEquals(BigDecimal("21.00"), f.vatLines[0].vatRate)
        assertEquals(BigDecimal("5.00"), f.vatLines[1].vatAmount)
        assertEquals(1, countRows("invoice_recognitions", id))
    }

    /** FR-004: what was not read stays empty, never invented. */
    @Test
    fun `a doubtful field stays empty`() {
        fake.script = { FakeInvoiceRecognizer.recognized(FakeInvoiceRecognizer.proposal(number = null, uncertainFields = listOf("number"))) }
        val id = upload(InvoiceSamples.pdf()).single().invoiceId!!
        waitForStatus(id, InvoiceStatus.DRAFT)
        assertNull(reader.invoice(id).number)
    }

    @Test
    fun `something that is not an invoice leaves a draft with no figures`() {
        fake.script = {
            FakeInvoiceRecognizer.recognized(
                FakeInvoiceRecognizer.proposal(
                    isInvoice = false, issuerName = null, issuerNif = null, recipientName = null,
                    recipientNif = null, number = null, issueDate = null, concept = null,
                    vatLines = emptyList(), withholdings = null, total = null
                )
            )
        }
        val id = upload(InvoiceSamples.png()).single().invoiceId!!
        waitForStatus(id, InvoiceStatus.DRAFT)
        val f = reader.invoice(id)
        assertNull(f.total)
        assertTrue(f.vatLines.isEmpty())
        assertEquals("NOT_AN_INVOICE", reader.lastRecognition(id)?.result)
    }

    @Test
    fun `a failed recognition leaves the invoice pending, with the attempt recorded`() {
        fake.script = { RecognitionResult.Failed("API_ERROR", FakeInvoiceRecognizer.MODEL) }
        val id = upload(InvoiceSamples.pdf()).single().invoiceId!!
        waitForAttempts(id, 1)

        assertEquals(InvoiceStatus.PENDING_RECOGNITION, reader.status(id))
        assertEquals("ERROR", reader.lastRecognition(id)?.result)
        assertEquals("API_ERROR", reader.lastRecognition(id)?.error)
    }

    @Test
    fun `the recogniser is never called inside a transaction`() {
        val ids = upload(InvoiceSamples.pdf(), InvoiceSamples.png()).map { it.invoiceId!! }
        ids.forEach { waitForStatus(it, InvoiceStatus.DRAFT) }
        assertTrue(fake.calls.size >= 2)
        assertTrue(fake.calls.none { it.inTransaction }, "a call held a database connection: ${fake.calls}")
    }
}
