package com.granatum.core

import com.granatum.core.domain.exception.DuplicateInvoiceException
import com.granatum.core.service.CompanyService
import com.granatum.core.service.InvoiceReader
import com.granatum.core.service.InvoiceReview
import com.granatum.core.service.InvoiceConcurrencyProbe
import com.granatum.core.service.InvoiceUploads
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Two photos of the same invoice cannot both count (FR-014, SC-006, research.md
 * D-015) - not even confirmed at the same instant from two threads.
 *
 * ## Validated by mutation (2026-10-08), in layers
 *
 * Three things stand in the way of a double confirmation, and the test shows
 * which ones are load-bearing:
 *
 * 1. **The service's duplicate check removed**: still green. The partial
 *    unique index refuses the second one, and the service turns that into
 *    `DUPLICATE_INVOICE`.
 * 2. **The unique index removed** (from V18): still green. Both confirmations
 *    lock the same quarter row (`SELECT ... FOR UPDATE`, D-016), so the second
 *    runs only after the first commits, and its check sees it.
 * 3. **Index and quarter lock both removed**: red - the two threads pass the
 *    check together (the probe makes them wait for each other) and both
 *    commit. So each of the two guarantees holds on its own.
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
@Import(WithFakeInvoiceRecognizer::class, LogicalDuplicateInvoiceIT.WithMeetingProbe::class)
class LogicalDuplicateInvoiceIT {

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

    /**
     * Makes two confirmations wait for each other right after their checks, so
     * that without a lock they would both pass them. With the lock, the second
     * never gets there while the first holds it: the wait times out and the
     * first carries on.
     */
    class MeetingProbe : InvoiceConcurrencyProbe {
        @Volatile var barrier: CyclicBarrier? = null
        override fun afterChecks(invoiceId: UUID) {
            try {
                barrier?.await(2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                // Alone at the meeting point: the other is waiting on the lock.
            }
        }
    }

    @TestConfiguration
    class WithMeetingProbe {
        @Bean @Primary fun meetingProbe() = MeetingProbe()
    }

    @Autowired lateinit var review: InvoiceReview
    @Autowired lateinit var uploads: InvoiceUploads
    @Autowired lateinit var reader: InvoiceReader
    @Autowired lateinit var company: CompanyService
    @Autowired lateinit var fake: FakeInvoiceRecognizer
    @Autowired lateinit var probe: MeetingProbe
    @Autowired lateinit var dataSource: DataSource

    private val e by lazy { InvoiceScenario(uploads, reader, fake, company, dataSource) }

    @BeforeEach
    fun setUp() {
        fake.reset()
        probe.barrier = null
        e.configureCompany()
    }

    private fun twoPhotosOfTheSameInvoice(): Pair<UUID, UUID> {
        val proposal = FakeInvoiceRecognizer.proposal(number = "D-${UUID.randomUUID().toString().take(8)}")
        return e.draft(proposal) to e.draft(proposal)
    }

    private fun confirmed(a: UUID, b: UUID): Int =
        e.text("SELECT count(*) FROM invoices WHERE id IN (?, ?) AND status = 'CONFIRMED'", a, b)!!.toInt()

    @Test
    fun `the second photo of a confirmed invoice cannot be confirmed`() {
        val (a, b) = twoPhotosOfTheSameInvoice()
        review.confirm(a, reader.invoice(a).version, e.admin)
        assertFailsWith<DuplicateInvoiceException> { review.confirm(b, reader.invoice(b).version, e.admin) }
        assertEquals(1, confirmed(a, b))
    }

    @Test
    fun `confirming both at the same instant still leaves one`() {
        val (a, b) = twoPhotosOfTheSameInvoice()
        probe.barrier = CyclicBarrier(2)
        val threads = Executors.newFixedThreadPool(2)
        val futures = listOf(a, b).map { id ->
            threads.submit<Throwable?> {
                try { review.confirm(id, reader.invoice(id).version, e.admin); null } catch (t: Throwable) { t }
            }
        }
        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        threads.shutdown()

        assertEquals(1, confirmed(a, b), "exactly one of the two counts")
        assertEquals(1, results.count { it is DuplicateInvoiceException }, "$results")
    }
}
