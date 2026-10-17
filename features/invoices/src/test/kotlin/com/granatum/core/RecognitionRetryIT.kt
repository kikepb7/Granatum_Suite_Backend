package com.granatum.core

import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.port.RecognitionResult
import com.granatum.core.scheduling.RecognitionRetryJob
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
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * Pending invoices are not forgotten: after a restart or a failure, the
 * scheduled job sends them again (SC-008) - but not for ever. After three
 * failed attempts an invoice is left for manual entry: one the model always
 * refuses would otherwise be sent, and paid for, every five minutes.
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
@Import(WithFakeInvoiceRecognizer::class)
class RecognitionRetryIT {

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
    @Autowired lateinit var job: RecognitionRetryJob
    @Autowired lateinit var fake: FakeInvoiceRecognizer
    @Autowired lateinit var reader: InvoiceReader
    @Autowired lateinit var dataSource: DataSource

    private val failure = { _: com.granatum.core.domain.port.RecognitionDocument ->
        RecognitionResult.Failed("API_ERROR", FakeInvoiceRecognizer.MODEL)
    }

    @BeforeEach
    fun reset() = fake.reset()

    private fun uploadFailing(): UUID {
        fake.script = failure
        val id = uploads.upload(listOf(UploadedInvoiceFile(null, InvoiceSamples.pdf())), UUID.randomUUID()).single().invoiceId!!
        waitForAttempts(id, 1)
        return id
    }

    private fun attempts(id: UUID): Int = dataSource.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM invoice_recognitions WHERE invoice_id = ?").use { s ->
            s.setObject(1, id); s.executeQuery().use { r -> r.next(); r.getInt(1) }
        }
    }

    private fun waitForAttempts(id: UUID, n: Int) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < deadline && attempts(id) < n) Thread.sleep(50)
        Thread.sleep(100)
    }

    private fun age(id: UUID) = dataSource.connection.use { c ->
        c.prepareStatement("UPDATE invoices SET uploaded_at = now() - interval '1 hour' WHERE id = ?").use { s ->
            s.setObject(1, id); s.executeUpdate()
        }
    }

    @Test
    fun `an old pending invoice is picked up again`() {
        val id = uploadFailing()
        age(id)
        fake.script = { FakeInvoiceRecognizer.recognized(FakeInvoiceRecognizer.proposal()) }

        job.retry()
        waitForAttempts(id, 2)

        assertEquals(InvoiceStatus.DRAFT, reader.status(id))
    }

    @Test
    fun `a recent pending invoice is left alone`() {
        val id = uploadFailing()
        val calls = fake.calls.size

        job.retry()
        Thread.sleep(300)

        assertEquals(calls, fake.calls.size)
        assertEquals(1, attempts(id))
    }

    @Test
    fun `after three failed attempts it is left for manual entry`() {
        val id = uploadFailing()
        age(id)
        job.retry(); waitForAttempts(id, 2)
        job.retry(); waitForAttempts(id, 3)
        assertEquals(3, attempts(id))

        job.retry()
        Thread.sleep(300)

        assertEquals(3, attempts(id), "no fourth paid attempt")
        assertEquals(InvoiceStatus.PENDING_RECOGNITION, reader.status(id), "still there, to be filled in by hand")
    }
}
