package com.granatum.core

import com.granatum.core.domain.contract.InvoiceSheetSource
import com.granatum.core.domain.contract.InvoiceSheetType
import com.granatum.core.domain.model.ProposedVatLine
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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 012, US3: the confirmed invoices as the receipts spreadsheet
 * receives them, through the `InvoiceSheetSource` contract (FR-015, FR-017).
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
@Import(WithFakeInvoiceRecognizer::class)
class InvoiceSheetSourceIT {

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

    @Autowired lateinit var source: InvoiceSheetSource
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

    private fun confirmed(issueDate: String = "2026-10-02", number: String = "F-${UUID.randomUUID().toString().take(8)}"): UUID {
        val id = e.draft(FakeInvoiceRecognizer.proposal(number = number, issueDate = issueDate))
        review.confirm(id, reader.invoice(id).version, e.admin)
        return id
    }

    @Test
    fun `a confirmed invoice gives its row, with base, VAT and surcharge summed over its VAT lines`() {
        val id = e.draft(
            FakeInvoiceRecognizer.proposal(
                number = "S-${UUID.randomUUID().toString().take(6)}",
                vatLines = listOf(
                    ProposedVatLine("21.00", "100.00", "21.00", "5.20", null),
                    ProposedVatLine("10.00", "50.00", "5.00", "0.70", null)
                ),
                total = "181.90"
            )
        )
        review.confirm(id, reader.invoice(id).version, e.admin)

        val row = assertNotNull(source.row(id))

        assertEquals(id, row.id)
        assertEquals(LocalDate.parse("2026-10-02"), row.issueDate)
        assertEquals(InvoiceSheetType.RECEIVED, row.type)
        assertEquals("Flores del Sur S.L.", row.issuerName)
        assertEquals("A58818501", row.issuerNif)
        assertEquals("Floristeria Granatum S.L.", row.recipientName)
        assertEquals("B12345674", row.recipientNif)
        assertEquals("Rosas rojas", row.concept)
        assertEquals(BigDecimal("150.00"), row.base)
        assertEquals(BigDecimal("26.00"), row.vatAmount)
        assertEquals(BigDecimal("5.90"), row.surcharge)
        assertEquals(BigDecimal("0.00"), row.withholdings)
        assertEquals(BigDecimal("181.90"), row.total)
        assertFalse(row.toString().contains("A58818501") || row.toString().contains("B12345674"), "toString hides the tax ids")
    }

    @Test
    fun `a draft, a pending or a discarded invoice gives no row, nor does a missing one`() {
        val draft = e.draft()
        assertNull(source.row(draft))

        val discarded = confirmed()
        review.discard(discarded, reader.invoice(discarded).version, e.admin)
        assertNull(source.row(discarded))

        assertNull(source.row(UUID.randomUUID()))
    }

    @Test
    fun `a corrected confirmed invoice gives its current values`() {
        val id = confirmed()
        review.correct(id, InvoiceValues.from(reader.invoice(id)).copy(concept = "Lirios"), reader.invoice(id).version, e.admin)
        assertEquals("Lirios", source.row(id)!!.concept)
    }

    @Test
    fun `a month holds the confirmed invoices issued in it, ordered by issue date`() {
        val late = confirmed("2026-07-30")
        val early = confirmed("2026-07-01")
        confirmed("2026-08-01")
        confirmed("2026-06-30")
        val draft = e.draft(FakeInvoiceRecognizer.proposal(number = "D-${UUID.randomUUID().toString().take(6)}", issueDate = "2026-07-15"))

        val rows = source.month(YearMonth.of(2026, 7))

        assertEquals(listOf(early, late), rows.map { it.id })
        assertTrue(rows.none { it.id == draft })
    }
}
