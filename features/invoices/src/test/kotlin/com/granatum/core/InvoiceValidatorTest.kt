package com.granatum.core

import com.granatum.core.domain.model.InvoiceNotice
import com.granatum.core.domain.model.NoVatCause
import com.granatum.core.domain.model.InvoiceNoticeCode
import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.model.Invoice
import com.granatum.core.domain.model.VatLine
import com.granatum.core.domain.model.InvoiceParty
import com.granatum.core.domain.model.InvoiceType
import com.granatum.core.domain.service.InvoiceValidationContext
import com.granatum.core.domain.service.InvoiceValidator
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What stops an invoice from being confirmed, and what only warns (FR-011 to
 * FR-014, contracts/README.md). Pure: no database, no clock.
 */
class InvoiceValidatorTest {

    private val today = LocalDate.parse("2026-10-08")
    private fun d(v: String) = BigDecimal(v)

    /** 100.00 at 21% + 50.00 at 10% = 150.00 + 21.00 + 5.00 = 176.00, from A58818501 to the company. */
    private fun invoice(
        issuerNif: String? = "A58818501",
        recipientNif: String? = "B12345674",
        number: String? = "2026-0815",
        date: LocalDate? = LocalDate.parse("2026-10-02"),
        currency: String = "EUR",
        vatLines: List<VatLine> = listOf(VatLine(d("21.00"), d("100.00"), d("21.00")), VatLine(d("10.00"), d("50.00"), d("5.00"))),
        withholdings: BigDecimal = d("0.00"),
        total: BigDecimal? = d("176.00"),
        corrective: Boolean = false,
        type: InvoiceType? = InvoiceType.RECEIVED
    ) = Invoice(
        UUID.randomUUID(), InvoiceStatus.DRAFT, type, InvoiceParty("Flores del Sur S.L.", issuerNif),
        recipientNif?.let { InvoiceParty("Floristeria Granatum S.L.", it) }, number, date, "Rosas", currency,
        vatLines, withholdings, total, corrective, 0
    )

    private fun notices(f: Invoice, ctx: InvoiceValidationContext = InvoiceValidationContext(today = today)) = InvoiceValidator.validate(f, ctx)
    private fun List<InvoiceNotice>.codes() = map { it.code }.toSet()
    private fun List<InvoiceNotice>.blockingCodes() = filter { it.blocking }.map { it.code }.toSet()

    @Test
    fun `a correct invoice has no warnings`() {
        assertEquals(emptyList(), notices(invoice()))
    }

    /** FR-013 */
    @Test
    fun `issuer tax id, number, date, at least one line, the total and the type are required`() {
        val a = notices(invoice(issuerNif = null, number = null, date = null, vatLines = emptyList(), total = null, type = null))
        assertEquals(setOf("issuer.nif", "number", "issueDate", "vatLines", "total", "type"),
            a.filter { it.code == InvoiceNoticeCode.REQUIRED }.map { it.field }.toSet())
        assertTrue(a.all { it.code != InvoiceNoticeCode.REQUIRED || it.blocking })
    }

    /** FR-011: bases + VAT + surcharge − withholdings = total, within one cent. */
    @Test
    fun `the total must add up, within one cent`() {
        assertFalse(InvoiceNoticeCode.TOTAL_MISMATCH in notices(invoice(total = d("176.01"))).codes(), "one cent of rounding")
        assertTrue(InvoiceNoticeCode.TOTAL_MISMATCH in notices(invoice(total = d("176.02"))).blockingCodes())
        assertTrue(InvoiceNoticeCode.TOTAL_MISMATCH in notices(invoice(total = d("175.00"))).blockingCodes())
        assertEquals(emptyList(), notices(invoice(withholdings = d("15.00"), total = d("161.00"))), "withholdings subtract")
        val withSurcharge = listOf(VatLine(d("21.00"), d("100.00"), d("21.00"), d("5.20")))
        assertEquals(emptyList(), notices(invoice(vatLines = withSurcharge, total = d("126.20"))), "surcharge adds")
    }

    @Test
    fun `the message of a mismatch shows the sums, not the parties`() {
        val notice = notices(invoice(total = d("175.00"))).single { it.code == InvoiceNoticeCode.TOTAL_MISMATCH }
        assertTrue(notice.message.contains("176.00") && notice.message.contains("175.00"))
        assertFalse(notice.message.contains("A58818501") || notice.message.contains("Flores"))
    }

    /** FR-012 */
    @Test
    fun `a tax id with a wrong control blocks, the issuer's or the recipient's`() {
        assertTrue(InvoiceNoticeCode.INVALID_NIF in notices(invoice(issuerNif = "A58818502")).blockingCodes())
        assertTrue(InvoiceNoticeCode.INVALID_NIF in notices(invoice(recipientNif = "B12345675")).blockingCodes())
        assertEquals(emptyList(), notices(invoice(issuerNif = "ES-A58818501")), "the VAT prefix and punctuation are fine")
    }

    @Test
    fun `a simplified invoice without recipient is fine`() {
        assertEquals(emptyList(), notices(invoice(recipientNif = null)))
    }

    @Test
    fun `negative amounts only in a credit note`() {
        val negativeLines = listOf(VatLine(d("21.00"), d("-100.00"), d("-21.00")))
        assertTrue(InvoiceNoticeCode.NEGATIVE_AMOUNT in notices(invoice(vatLines = negativeLines, total = d("-121.00"))).blockingCodes())
        assertEquals(emptyList(), notices(invoice(vatLines = negativeLines, total = d("-121.00"), corrective = true)))
    }

    /** Research D-020: a line with no VAT must say why. */
    @Test
    fun `a zero-VAT line needs a cause`() {
        val withoutCause = listOf(VatLine(d("0.00"), d("100.00"), d("0.00")))
        assertTrue(InvoiceNoticeCode.MISSING_NO_VAT_CAUSE in notices(invoice(vatLines = withoutCause, total = d("100.00"))).blockingCodes())
        val exempt = listOf(VatLine(d("0.00"), d("100.00"), d("0.00"), noVatCause = NoVatCause.EXEMPT))
        assertEquals(emptyList(), notices(invoice(vatLines = exempt, total = d("100.00"))))
    }

    @Test
    fun `a currency other than euro blocks`() {
        assertTrue(InvoiceNoticeCode.CURRENCY in notices(invoice(currency = "USD")).blockingCodes())
    }

    @Test
    fun `a future date or one older than four years only warns`() {
        val future = notices(invoice(date = today.plusDays(1)))
        assertEquals(setOf(InvoiceNoticeCode.FUTURE_DATE), future.codes())
        assertTrue(future.blockingCodes().isEmpty())
        val old = notices(invoice(date = today.minusYears(4).minusDays(1)))
        assertEquals(setOf(InvoiceNoticeCode.OLD_DATE), old.codes())
        assertTrue(old.blockingCodes().isEmpty())
    }

    @Test
    fun `context warnings - doubtful fields, duplicates, closed quarter, what the recognition said`() {
        val ctx = InvoiceValidationContext(
            today = today,
            uncertainFields = listOf("number"),
            duplicate = true,
            quarterClosed = true,
            recognitionResult = "MULTIPLE_INVOICES",
            notCompanyInvoice = true
        )
        val a = notices(invoice(), ctx)
        assertEquals(setOf(InvoiceNoticeCode.UNCERTAIN, InvoiceNoticeCode.DUPLICATE, InvoiceNoticeCode.QUARTER_CLOSED, InvoiceNoticeCode.MULTIPLE_INVOICES, InvoiceNoticeCode.NOT_COMPANY_INVOICE), a.codes())
        assertEquals(setOf(InvoiceNoticeCode.DUPLICATE, InvoiceNoticeCode.QUARTER_CLOSED), a.blockingCodes(), "doubtful, several and not-ours only warn")
        assertEquals("number", a.single { it.code == InvoiceNoticeCode.UNCERTAIN }.field)
        assertTrue(InvoiceNoticeCode.NOT_AN_INVOICE in notices(invoice(), InvoiceValidationContext(today = today, recognitionResult = "NOT_AN_INVOICE")).codes())
    }
}
