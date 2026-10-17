package com.granatum.core.service

import com.granatum.core.domain.exception.QuarterClosedException
import com.granatum.core.domain.model.ReportPeriod
import com.granatum.core.infrastructure.database.entities.QuarterEntity
import com.granatum.core.infrastructure.database.entities.QuarterId
import com.granatum.core.infrastructure.database.repositories.QuarterRepository
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * The quarter lock (research.md D-016). Everything that changes an invoice of a
 * quarter, and closing the quarter itself, takes `SELECT … FOR UPDATE` on the
 * quarter's row first: they cannot interleave. Must run inside the caller's
 * transaction.
 */
@Component
class QuarterLock(private val quarters: QuarterRepository) {

    fun lock(year: Int, quarter: Int): QuarterEntity {
        quarters.createIfMissing(year.toShort(), quarter.toShort())
        return quarters.lock(QuarterId(year.toShort(), quarter.toShort()))
    }

    fun lock(date: LocalDate): QuarterEntity = lock(date.year, ReportPeriod.quarterOf(date))

    /** Locks the quarter of [date] and refuses if it is closed (FR-031). */
    fun requireOpen(date: LocalDate) {
        val t = lock(date)
        if (t.closed) throw QuarterClosedException(date.year, ReportPeriod.quarterOf(date))
    }

    /** Without locking: for displaying, not for deciding. */
    fun isClosed(date: LocalDate): Boolean =
        quarters.findById(QuarterId(date.year.toShort(), ReportPeriod.quarterOf(date).toShort()))
            .map { it.closed }.orElse(false)
}
