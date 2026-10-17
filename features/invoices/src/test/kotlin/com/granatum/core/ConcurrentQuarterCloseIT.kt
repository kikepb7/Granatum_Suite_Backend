package com.granatum.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.granatum.core.domain.model.ReportPeriod
import com.granatum.core.service.CompanyService
import com.granatum.core.service.InvoiceReader
import com.granatum.core.service.InvoicingReports
import com.granatum.core.service.InvoiceReview
import com.granatum.core.service.InvoiceConcurrencyProbe
import com.granatum.core.service.InvoiceUploads
import com.granatum.core.service.Quarters
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
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Research D-016, SC-009: closing a quarter and changing one of its invoices
 * cannot interleave, so a closed quarter always equals its snapshot.
 *
 * The race is forced, not hoped for (finding T1): a probe stops a discard of a
 * confirmed invoice right after its checks - quarter open - and a close is
 * started meanwhile. With the lock, the close waits for the discard and its
 * snapshot leaves the invoice out. Without it, the close would snapshot the
 * invoice and the discard would commit just after, changing a closed quarter.
 *
 * Confirming a draft is not the dangerous case: a draft counts as pending, and
 * a quarter with pending invoices refuses to close at all.
 *
 * Validated by mutation (2026-10-08): with the `@Lock(PESSIMISTIC_WRITE)`
 * removed from `QuarterRepository.lock`, the close no longer waited and
 * this test went red (the report no longer matched the snapshot); restored.
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
@Import(WithFakeInvoiceRecognizer::class, ConcurrentQuarterCloseIT.WithStoppingProbe::class)
class ConcurrentQuarterCloseIT {

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

    /** Holds the next operation that reaches the probe until released. */
    class StoppingProbe : InvoiceConcurrencyProbe {
        @Volatile var enabled = false
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun afterChecks(invoiceId: UUID) {
            if (!enabled) return
            arrived.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
    }

    @TestConfiguration
    class WithStoppingProbe {
        @Bean @Primary fun stoppingProbe() = StoppingProbe()
    }

    @Autowired lateinit var quarters: Quarters
    @Autowired lateinit var review: InvoiceReview
    @Autowired lateinit var reports: InvoicingReports
    @Autowired lateinit var uploads: InvoiceUploads
    @Autowired lateinit var reader: InvoiceReader
    @Autowired lateinit var company: CompanyService
    @Autowired lateinit var fake: FakeInvoiceRecognizer
    @Autowired lateinit var probe: StoppingProbe
    @Autowired lateinit var dataSource: DataSource

    private val e by lazy { InvoiceScenario(uploads, reader, fake, company, dataSource) }

    @Test
    fun `a close and a discard of the same quarter never leave it different from its snapshot`() {
        fake.reset()
        e.configureCompany()
        val ids = listOf("2025-02-10", "2025-03-05").map { date ->
            val id = e.draft(FakeInvoiceRecognizer.proposal(number = "C-${UUID.randomUUID().toString().take(8)}", issueDate = date))
            review.confirm(id, reader.invoice(id).version, e.admin)
            id
        }

        probe.enabled = true
        val threads = Executors.newFixedThreadPool(2)
        val discarding = threads.submit { review.discard(ids[0], reader.invoice(ids[0]).version, e.admin) }
        assertTrue(probe.arrived.await(10, TimeUnit.SECONDS), "the discard reached the probe")
        probe.enabled = false

        val closing = threads.submit { quarters.close(2025, 1, e.admin) }
        Thread.sleep(700)
        val closeWaited = !closing.isDone

        probe.release.countDown()
        discarding.get(20, TimeUnit.SECONDS)
        closing.get(20, TimeUnit.SECONDS)
        threads.shutdown()

        val snapshot = ObjectMapper().readTree(
            e.text("SELECT totals::text FROM quarter_events WHERE year = 2025 AND quarter = 1 AND action = 'CLOSE'")
        )
        val report = reports.calculate(ReportPeriod.Quarterly(2025, 1))
        assertEquals(report.received.invoices, snapshot["received"]["invoices"].asInt(), "the closed quarter equals its snapshot")
        assertEquals(report.received.total, BigDecimal(snapshot["received"]["total"].asText()))
        assertEquals(1, report.received.invoices, "the discarded invoice is out of both")
        assertTrue(closeWaited, "the close waited for the discard holding the quarter lock")
    }
}
