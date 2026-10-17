package com.granatum.core.domain.service

import com.granatum.core.domain.model.Absence
import com.granatum.core.domain.model.AbsenceStatus
import com.granatum.core.domain.model.AbsenceType
import com.granatum.core.domain.model.VacationBalance
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Holiday balance, pure (feature 007, FR-014 to FR-017, research.md D-001).
 *
 * Calendar days of each holiday request that fall inside the year: 28 Dec - 4
 * Jan is 4 days of one year and 4 of the next. Only VACATION counts; leave and
 * sick leave never do. Cancelled and rejected requests give their days back.
 */
object AbsenceBalanceCalculator {

    fun balance(year: Int, entitlement: Int, absences: Collection<Absence>): VacationBalance {
        val vacations = absences.filter { it.type == AbsenceType.VACATION }
        return VacationBalance(
            year = year,
            entitlement = entitlement,
            approved = vacations.filter { it.status == AbsenceStatus.APPROVED }.sumOf { daysInYear(it.from, it.to!!, year) },
            pending = vacations.filter { it.status == AbsenceStatus.PENDING }.sumOf { daysInYear(it.from, it.to!!, year) }
        )
    }

    /** Days of the inclusive range [from, to] that fall in [year]. */
    fun daysInYear(from: LocalDate, to: LocalDate, year: Int): Int {
        val start = maxOf(from, LocalDate.of(year, 1, 1))
        val end = minOf(to, LocalDate.of(year, 12, 31))
        return if (end.isBefore(start)) 0 else ChronoUnit.DAYS.between(start, end).toInt() + 1
    }

    /** The years an inclusive range touches - at most two, since a range is at most 366 days. */
    fun years(from: LocalDate, to: LocalDate): IntRange = from.year..to.year
}
