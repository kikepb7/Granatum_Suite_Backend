package com.granatum.core.service

import com.granatum.core.domain.exception.InvalidReportPeriodException
import com.granatum.core.domain.model.ReportPeriod
import com.granatum.core.domain.model.InvoicingReport
import com.granatum.core.domain.model.ClosedQuarter
import com.granatum.core.domain.service.InvoicingReportCalculator
import com.granatum.core.infrastructure.database.toDomain
import com.granatum.core.infrastructure.database.entities.QuarterEventEntity
import com.granatum.core.infrastructure.database.entities.QuarterId
import com.granatum.core.infrastructure.database.repositories.InvoiceRepository
import com.granatum.core.infrastructure.database.repositories.QuarterEventRepository
import com.granatum.core.infrastructure.database.repositories.QuarterRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Reports by period (FR-018 to FR-023, FR-033). The figures all come from [InvoicingReportCalculator]. */
@Service
class InvoicingReports(
    private val invoices: InvoiceRepository,
    private val quarters: QuarterRepository,
    private val events: QuarterEventRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    /** The period asked for, or `INVALID_PERIOD` for an impossible combination. */
    fun period(type: String, year: Int, month: Int?, quarter: Int?): ReportPeriod {
        if (year !in 2000..2100) throw InvalidReportPeriodException("El ano tiene que estar entre 2000 y 2100")
        return when (type.uppercase()) {
            "MONTHLY" -> {
                if (quarter != null) throw InvalidReportPeriodException("Un periodo mensual no lleva trimestre")
                if (month == null || month !in 1..12) throw InvalidReportPeriodException("El mes tiene que estar entre 1 y 12")
                ReportPeriod.Monthly(year, month)
            }
            "QUARTERLY" -> {
                if (month != null) throw InvalidReportPeriodException("Un periodo trimestral no lleva mes")
                if (quarter == null || quarter !in 1..4) throw InvalidReportPeriodException("El trimestre tiene que estar entre 1 y 4")
                ReportPeriod.Quarterly(year, quarter)
            }
            "YEARLY" -> {
                if (month != null || quarter != null) throw InvalidReportPeriodException("Un periodo anual no lleva mes ni trimestre")
                ReportPeriod.Yearly(year)
            }
            else -> throw InvalidReportPeriodException("El periodo es MENSUAL, TRIMESTRAL o ANUAL")
        }
    }

    @Transactional(readOnly = true)
    fun calculate(period: ReportPeriod): InvoicingReport = InvoicingReportCalculator.calculate(
        period = period,
        confirmed = invoices.findConfirmedBetween(period.from, period.to).map { it.toDomain() },
        pending = invoices.countPendingBetween(period.from, period.to).toInt(),
        closedQuarters = closedQuarters(period),
        calculatedAt = clock.instant()
    )

    /** FR-033: the closed quarters the period touches, and since when. */
    private fun closedQuarters(period: ReportPeriod): List<ClosedQuarter> {
        val touched = when (period) {
            is ReportPeriod.Monthly -> listOf(ReportPeriod.quarterOf(period.from))
            is ReportPeriod.Quarterly -> listOf(period.quarter)
            is ReportPeriod.Yearly -> (1..4).toList()
        }
        return touched.mapNotNull { t ->
            val year = period.year.toShort()
            val row = quarters.findById(QuarterId(year, t.toShort())).orElse(null)
            if (row == null || !row.closed) return@mapNotNull null
            val from = events.findAllByYearAndQuarterOrderByOccurredAtAsc(year, t.toShort())
                .lastOrNull { it.action == QuarterEventEntity.CLOSE }?.occurredAt ?: return@mapNotNull null
            ClosedQuarter(period.year, t, from)
        }
    }
}
