package com.granatum.core

import com.granatum.core.domain.event.InvoiceSheetChange
import com.granatum.core.service.CompanyService
import com.granatum.core.service.InvoiceReader
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
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feature 012, US3 (FR-015, FR-016, FR-017): reviewing an invoice publishes
 * `InvoiceSheetChange` exactly when the Google spreadsheet must change -
 * confirming, and correcting or discarding a confirmed invoice - and never for
 * an invoice that was not confirmed.
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
@Import(WithFakeInvoiceRecognizer::class)
@RecordApplicationEvents
class InvoiceSheetChangeEventsIT {

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

    @Autowired lateinit var events: ApplicationEvents
    @Autowired lateinit var review: InvoiceReview
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

    private fun changes(): List<InvoiceSheetChange> = events.stream(InvoiceSheetChange::class.java).toList()
    private fun values(id: UUID) = InvoiceValues.from(reader.invoice(id))

    @Test
    fun `uploading and correcting or discarding a draft publish nothing`() {
        val corrected = e.draft()
        val discarded = e.draft()
        assertTrue(changes().isEmpty(), "the upload and the recognition publish nothing")

        review.correct(corrected, values(corrected).copy(concept = "Tulipanes"), reader.invoice(corrected).version, e.admin)
        review.discard(discarded, reader.invoice(discarded).version, e.admin)

        assertTrue(changes().isEmpty(), "a draft never reaches the spreadsheet")
    }

    @Test
    fun `confirming publishes the change with the invoice id`() {
        val id = e.draft()
        events.clear()

        review.confirm(id, reader.invoice(id).version, e.admin)

        assertEquals(listOf(InvoiceSheetChange(id)), changes())
    }

    @Test
    fun `correcting a confirmed invoice publishes the change`() {
        val id = e.draft()
        val confirmed = review.confirm(id, reader.invoice(id).version, e.admin)
        events.clear()

        review.correct(id, values(id).copy(concept = "Rosas blancas"), confirmed.version, e.admin)

        assertEquals(listOf(InvoiceSheetChange(id)), changes())
    }

    @Test
    fun `discarding a confirmed invoice publishes the change`() {
        val id = e.draft()
        val confirmed = review.confirm(id, reader.invoice(id).version, e.admin)
        events.clear()

        review.discard(id, confirmed.version, e.admin)

        assertEquals(listOf(InvoiceSheetChange(id)), changes())
    }

    @Test
    fun `a refused confirmation publishes nothing`() {
        val id = e.draft(FakeInvoiceRecognizer.proposal(number = "N-${UUID.randomUUID().toString().take(6)}", total = "175.00"))
        events.clear()

        runCatching { review.confirm(id, reader.invoice(id).version, e.admin) }

        assertTrue(changes().isEmpty())
    }
}
