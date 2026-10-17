package com.granatum.core.service

import com.granatum.core.api.dto.toDto
import com.granatum.core.domain.exception.InvalidReportPeriodException
import com.granatum.core.domain.exception.QuarterOpenException
import com.granatum.core.domain.exception.QuarterClosedException
import com.granatum.core.domain.exception.QuarterHasPendingException
import com.granatum.core.domain.model.ReportPeriod
import com.granatum.core.infrastructure.database.InvoicingJson
import com.granatum.core.infrastructure.database.entities.QuarterEventEntity
import com.granatum.core.infrastructure.database.repositories.InvoiceRepository
import com.granatum.core.infrastructure.database.repositories.QuarterEventRepository
import com.granatum.core.infrastructure.database.repositories.QuarterRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class QuarterEvent(val action: String, val authorId: UUID, val occurredAt: Instant, val reason: String?)

data class QuarterStatus(val quarter: Int, val closed: Boolean, val events: List<QuarterEvent>)

/**
 * Closing and reopening fiscal quarters (FR-030 to FR-033, research.md D-016).
 *
 * A close takes the quarter's row lock - the same one every change to an
 * invoice of the quarter takes ([QuarterLock]) - so it waits for any
 * change in flight and nothing slips in after it. It refuses while drafts or
 * pending invoices of the quarter remain, and stores a snapshot of the report
 * totals: anyone can check later that a declared quarter has not changed.
 */
@Service
class Quarters(
    private val quarterLock: QuarterLock,
    private val quarters: QuarterRepository,
    private val events: QuarterEventRepository,
    private val invoices: InvoiceRepository,
    private val reports: InvoicingReports,
    private val json: InvoicingJson,
    private val clock: Clock = Clock.systemUTC()
) {
    @Transactional(readOnly = true)
    fun status(year: Int): List<QuarterStatus> {
        val rows = quarters.findAllByIdYearOrderByIdQuarter(year.toShort()).associateBy { it.id.quarter.toInt() }
        val byQuarter = events.findAllByYearOrderByOccurredAtAsc(year.toShort()).groupBy { it.quarter.toInt() }
        return (1..4).map { t ->
            QuarterStatus(
                t,
                rows[t]?.closed ?: false,
                byQuarter[t].orEmpty().map { QuarterEvent(it.action, it.authorId, it.occurredAt, it.reason) }
            )
        }
    }

    @Transactional
    fun close(year: Int, quarter: Int, author: UUID) {
        validate(year, quarter)
        val row = quarterLock.lock(year, quarter)
        if (row.closed) throw QuarterClosedException(year, quarter)

        val period = ReportPeriod.Quarterly(year, quarter)
        val pending = invoices.countPendingBetween(period.from, period.to)
        if (pending > 0) throw QuarterHasPendingException(pending.toInt())

        val report = reports.calculate(period)
        events.save(
            QuarterEventEntity(
                year = year.toShort(), quarter = quarter.toShort(), action = QuarterEventEntity.CLOSE,
                reason = null,
                // Amounts as decimal strings, as in the API: a JSON number would
                // come back as 352.0 and lose the cents' scale this record exists to prove.
                totals = json.write(report.toDto().let { mapOf("issued" to it.issued, "received" to it.received) }),
                authorId = author, occurredAt = clock.instant()
            )
        )
        row.closed = true
    }

    @Transactional
    fun reopen(year: Int, quarter: Int, reason: String, author: UUID) {
        validate(year, quarter)
        val row = quarterLock.lock(year, quarter)
        if (!row.closed) throw QuarterOpenException(year, quarter)
        events.save(
            QuarterEventEntity(
                year = year.toShort(), quarter = quarter.toShort(), action = QuarterEventEntity.REOPEN,
                reason = reason.trim(), totals = null, authorId = author, occurredAt = clock.instant()
            )
        )
        row.closed = false
    }

    private fun validate(year: Int, quarter: Int) {
        if (year !in 2000..2100 || quarter !in 1..4) throw InvalidReportPeriodException("Trimestre $year-T$quarter fuera de rango")
    }
}
