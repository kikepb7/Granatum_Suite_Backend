package com.granatum.core

import com.granatum.core.domain.exception.RecognitionUnavailableException
import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.port.InvoiceRecognizer
import com.granatum.core.infrastructure.claude.DisabledInvoiceRecognizer
import com.granatum.core.service.InvoiceReader
import com.granatum.core.service.UploadedInvoiceFile
import com.granatum.core.service.InvoiceUploads
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * FR-006, SC-008: without an API key nothing is sent anywhere, and invoices
 * are still stored, waiting to be filled in by hand (research.md D-006).
 *
 * No double here: this is the context the module's configuration builds on
 * its own, with the key pinned to an empty value. Filling an invoice in by
 * hand is tested with the corrections, in InvoiceReviewIT.
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
class ManualModeIT {

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

    @Autowired lateinit var recognizer: InvoiceRecognizer
    @Autowired lateinit var uploads: InvoiceUploads
    @Autowired lateinit var reader: InvoiceReader

    @Test
    fun `without a key the real client is never built`() {
        assertIs<DisabledInvoiceRecognizer>(recognizer)
    }

    @Test
    fun `an upload is stored and stays pending, and a retry is refused`() {
        val id = uploads.upload(listOf(UploadedInvoiceFile(null, InvoiceSamples.pdf())), UUID.randomUUID()).single().invoiceId!!
        Thread.sleep(500)
        assertEquals(InvoiceStatus.PENDING_RECOGNITION, reader.status(id))
        assertFailsWith<RecognitionUnavailableException> { uploads.retry(id) }
    }
}
