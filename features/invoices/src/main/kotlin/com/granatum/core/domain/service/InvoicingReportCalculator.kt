package com.granatum.core.domain.service

import com.granatum.core.domain.model.NoVatCause
import com.granatum.core.domain.model.Invoice
import com.granatum.core.domain.model.ReportPeriod
import com.granatum.core.domain.model.InvoicingReport
import com.granatum.core.domain.model.InvoiceType
import com.granatum.core.domain.model.GroupTotals
import com.granatum.core.domain.model.ClosedQuarter
import java.math.BigDecimal
import java.time.Instant

/**
 * The report of a period from its confirmed invoices (FR-018 to FR-023).
 *
 * One pure function, the single source of every figure: the screen, the CSV,
 * the PDF and the snapshot taken when a quarter is closed all come from here,
 * so they cannot disagree (SC-010). Sums in `BigDecimal`, exact to the cent
 * (research.md D-013).
 *
 * Credit notes carry negative amounts and so subtract (FR-023). A credit note
 * correcting an invoice upwards carries positive amounts and adds, which is
 * what it means.
 */
object InvoicingReportCalculator {

    private val ZERO_AMOUNT = BigDecimal.ZERO.setScale(2)

    fun calculate(
        period: ReportPeriod,
        confirmed: List<Invoice>,
        pending: Int,
        closedQuarters: List<ClosedQuarter>,
        calculatedAt: Instant
    ): InvoicingReport = InvoicingReport(
        period = period,
        issued = totals(confirmed.filter { it.type == InvoiceType.ISSUED }),
        received = totals(confirmed.filter { it.type == InvoiceType.RECEIVED }),
        pending = pending,
        closedQuarters = closedQuarters,
        calculatedAt = calculatedAt
    )

    private fun totals(invoices: List<Invoice>): GroupTotals {
        if (invoices.isEmpty()) return GroupTotals.EMPTY
        val vatLines = invoices.flatMap { it.vatLines }
        val vat = vatLines.filter { it.vatAmount.signum() != 0 }
            .groupBy { it.vatRate.setScale(2) }
            .mapValues { (_, ls) -> ls.fold(ZERO_AMOUNT) { s, l -> s + l.vatAmount } }
            .toSortedMap()
        val noVatBase = vatLines.filter { it.noVatCause != null }
            .groupBy { it.noVatCause!! }
            .mapValues { (_, ls) -> ls.fold(ZERO_AMOUNT) { s, l -> s + l.base } }
        return GroupTotals(
            invoices = invoices.size,
            base = vatLines.fold(ZERO_AMOUNT) { s, l -> s + l.base },
            vatByRate = LinkedHashMap(vat),
            surcharge = vatLines.fold(ZERO_AMOUNT) { s, l -> s + l.surcharge },
            withholdings = invoices.fold(ZERO_AMOUNT) { s, f -> s + f.withholdings },
            total = invoices.fold(ZERO_AMOUNT) { s, f -> s + (f.total ?: ZERO_AMOUNT) },
            noVatBase = NoVatCause.entries.filter { it in noVatBase }.associateWith { noVatBase.getValue(it) }
        )
    }
}
