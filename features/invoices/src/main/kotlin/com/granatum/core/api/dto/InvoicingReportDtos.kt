package com.granatum.core.api.dto

import com.granatum.core.domain.model.ReportPeriod
import com.granatum.core.domain.model.InvoicingReport
import com.granatum.core.domain.model.GroupTotals
import java.time.Instant
import java.time.LocalDate

data class ReportPeriodDto(val type: String, val year: Int, val month: Int?, val quarter: Int?, val from: LocalDate, val to: LocalDate)

data class ReportTotalsDto(
    val invoices: Int,
    val base: String,
    val vatByRate: Map<String, String>,
    val surcharge: String,
    val withholdings: String,
    val total: String,
    val noVatBase: Map<String, String>
)

data class ClosedQuarterDto(val year: Int, val quarter: Int, val from: Instant)

data class InvoicingReportDto(
    val period: ReportPeriodDto,
    val issued: ReportTotalsDto,
    val received: ReportTotalsDto,
    val pending: Int,
    val closedQuarters: List<ClosedQuarterDto>,
    val calculatedAt: Instant
)

private fun GroupTotals.toDto() = ReportTotalsDto(
    invoices, base.toPlainString(),
    vatByRate.entries.associate { (t, c) -> t.toPlainString() to c.toPlainString() },
    surcharge.toPlainString(), withholdings.toPlainString(), total.toPlainString(),
    noVatBase.entries.associate { (c, b) -> c.name to b.toPlainString() }
)

fun InvoicingReport.toDto() = InvoicingReportDto(
    period = when (val p = period) {
        is ReportPeriod.Monthly -> ReportPeriodDto("MONTHLY", p.year, p.month, null, p.from, p.to)
        is ReportPeriod.Quarterly -> ReportPeriodDto("QUARTERLY", p.year, null, p.quarter, p.from, p.to)
        is ReportPeriod.Yearly -> ReportPeriodDto("YEARLY", p.year, null, null, p.from, p.to)
    },
    issued = issued.toDto(),
    received = received.toDto(),
    pending = pending,
    closedQuarters = closedQuarters.map { ClosedQuarterDto(it.year, it.quarter, it.from) },
    calculatedAt = calculatedAt
)
