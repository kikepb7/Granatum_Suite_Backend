package com.granatum.core

import com.granatum.core.domain.model.NoVatCause
import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.model.Invoice
import com.granatum.core.domain.model.VatLine
import com.granatum.core.domain.model.InvoiceParty
import com.granatum.core.domain.model.ReportPeriod
import com.granatum.core.domain.model.InvoiceType
import com.granatum.core.domain.service.InvoicingReportCalculator
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

/** FR-018 to FR-023: exact totals, by group and VAT rate. Pure. */
class InvoicingReportCalculatorTest {

    private fun d(v: String) = BigDecimal(v)
    private val now = Instant.parse("2026-10-08T10:00:00Z")

    private fun invoice(
        type: InvoiceType,
        date: String,
        vatLines: List<VatLine>,
        withholdings: String = "0.00",
        corrective: Boolean = false
    ): Invoice {
        val total = vatLines.fold(BigDecimal.ZERO) { s, l -> s + l.base + l.vatAmount + l.surcharge } - d(withholdings)
        return Invoice(UUID.randomUUID(), InvoiceStatus.CONFIRMED, type, InvoiceParty("X", "A58818501"), null, "N",
            LocalDate.parse(date), null, "EUR", vatLines, d(withholdings), total, corrective, 0)
    }

    private fun line(type: String, base: String, vatAmount: String, surcharge: String = "0.00", cause: NoVatCause? = null) =
        VatLine(d(type), d(base), d(vatAmount), d(surcharge), cause)

    @Test
    fun `issued and received are totalled apart, VAT by rate`() {
        val invoices = listOf(
            invoice(InvoiceType.ISSUED, "2026-07-10", listOf(line("21.00", "100.00", "21.00"))),
            invoice(InvoiceType.ISSUED, "2026-08-10", listOf(line("21.00", "200.00", "42.00"), line("10.00", "50.00", "5.00"))),
            invoice(InvoiceType.RECEIVED, "2026-09-10", listOf(line("21.00", "1000.00", "210.00")), withholdings = "150.00")
        )
        val r = InvoicingReportCalculator.calculate(ReportPeriod.Quarterly(2026, 3), invoices, pending = 2, closedQuarters = emptyList(), calculatedAt = now)

        assertEquals(2, r.issued.invoices)
        assertEquals(d("350.00"), r.issued.base)
        assertEquals(mapOf(d("10.00") to d("5.00"), d("21.00") to d("63.00")), r.issued.vatByRate)
        assertEquals(d("418.00"), r.issued.total)
        assertEquals(1, r.received.invoices)
        assertEquals(d("150.00"), r.received.withholdings)
        assertEquals(d("1060.00"), r.received.total)
        assertEquals(2, r.pending)
        assertEquals(listOf(d("10.00"), d("21.00")), r.issued.vatByRate.keys.toList(), "ordered by rate")
    }

    @Test
    fun `surcharge and lines without VAT are reported on their own`() {
        val invoices = listOf(
            invoice(InvoiceType.RECEIVED, "2026-07-01", listOf(line("21.00", "100.00", "21.00", surcharge = "5.20"))),
            invoice(InvoiceType.RECEIVED, "2026-07-02", listOf(line("0.00", "320.00", "0.00", cause = NoVatCause.INTRA_COMMUNITY))),
            invoice(InvoiceType.RECEIVED, "2026-07-03", listOf(line("0.00", "80.00", "0.00", cause = NoVatCause.EXEMPT)))
        )
        val r = InvoicingReportCalculator.calculate(ReportPeriod.Monthly(2026, 7), invoices, 0, emptyList(), now)
        assertEquals(d("5.20"), r.received.surcharge)
        assertEquals(mapOf(NoVatCause.INTRA_COMMUNITY to d("320.00"), NoVatCause.EXEMPT to d("80.00")), r.received.noVatBase)
        assertEquals(mapOf(d("21.00") to d("21.00")), r.received.vatByRate, "a 0% line is not a VAT rate with tax")
    }

    /** FR-023: a credit note's negative amounts subtract. */
    @Test
    fun `credit notes subtract`() {
        val invoices = listOf(
            invoice(InvoiceType.ISSUED, "2026-07-01", listOf(line("21.00", "100.00", "21.00"))),
            invoice(InvoiceType.ISSUED, "2026-07-20", listOf(line("21.00", "-250.00", "-52.50")), corrective = true)
        )
        val r = InvoicingReportCalculator.calculate(ReportPeriod.Monthly(2026, 7), invoices, 0, emptyList(), now)
        assertEquals(d("-150.00"), r.issued.base)
        assertEquals(d("-181.50"), r.issued.total)
        assertEquals(2, r.issued.invoices)
    }

    /** FR-020: three months add up to their quarter, four quarters to the year. */
    @Test
    fun `smaller periods add up to the period that contains them`() {
        val r = java.util.Random(7)
        val invoices = (1..120).map { i ->
            val date = LocalDate.of(2026, 1, 1).plusDays(r.nextInt(365).toLong())
            val base = BigDecimal(r.nextInt(100_000)).movePointLeft(2)
            val vatAmount = (base * d("0.21")).setScale(2, java.math.RoundingMode.HALF_UP)
            invoice(if (i % 2 == 0) InvoiceType.ISSUED else InvoiceType.RECEIVED, date.toString(), listOf(line("21.00", base.toPlainString(), vatAmount.toPlainString())))
        }
        fun from(p: ReportPeriod) = InvoicingReportCalculator.calculate(p, invoices.filter { it.issueDate!! in p.from..p.to }, 0, emptyList(), now)

        val yearly = from(ReportPeriod.Yearly(2026))
        val quarters = (1..4).map { from(ReportPeriod.Quarterly(2026, it)) }
        assertEquals(yearly.issued.total, quarters.fold(BigDecimal.ZERO) { s, t -> s + t.issued.total })
        assertEquals(yearly.received.base, quarters.fold(BigDecimal.ZERO) { s, t -> s + t.received.base })
        (1..4).forEach { t ->
            val months = ((t - 1) * 3 + 1..t * 3).map { from(ReportPeriod.Monthly(2026, it)) }
            assertEquals(quarters[t - 1].issued.total, months.fold(BigDecimal.ZERO) { s, m -> s + m.issued.total })
            assertEquals(quarters[t - 1].received.invoices, months.sumOf { it.received.invoices })
        }
        assertEquals(120, yearly.issued.invoices + yearly.received.invoices)
    }

    @Test
    fun `an empty period has zero totals, not missing ones`() {
        val r = InvoicingReportCalculator.calculate(ReportPeriod.Yearly(2025), emptyList(), 0, emptyList(), now)
        assertEquals(0, r.issued.invoices)
        assertEquals(d("0.00"), r.issued.total)
    }
}
