package com.granatum.core.domain.service

import com.granatum.core.api.util.CivilDateRange
import com.granatum.core.domain.model.ShiftSummaryDay
import com.granatum.core.domain.model.ShiftModel
import com.granatum.core.domain.model.MonthlyShiftSummary
import com.granatum.core.domain.type.ContractType
import java.time.Duration
import java.util.UUID

/**
 * Aggregates a month of shifts. Pure, so it can be tested without a database.
 *
 * Reflects the values **currently in force** after approved corrections, not
 * the originals (FR-034) - the summary is what the month looked like, and the
 * originals belong in the correction history where their authorship is
 * recorded alongside them.
 */
object MonthlySummaryCalculator {

    fun calculate(
        employeeId: UUID,
        year: Int,
        month: Int,
        contractType: ContractType,
        shifts: List<ShiftModel>,
        correctedShifts: Set<UUID>
    ): MonthlyShiftSummary {
        val days = shifts
            .sortedBy { it.clockIn }
            .map { shift ->
                val breakMinutes = shift.breaks
                    .filter { it.end != null }
                    .fold(Duration.ZERO) { total, p ->
                        total + Duration.between(p.start, p.end)
                    }
                    .toMinutes()
                    .toInt()

                ShiftSummaryDay(
                    // The civil date of the entry, in Europe/Madrid: a shift
                    // starting at 00:30 Spanish summer time belongs to that day
                    // and not to the previous one, which is what UTC would say.
                    date = CivilDateRange.civilDate(shift.clockIn),
                    clockIn = shift.clockIn,
                    clockOut = shift.clockOut,
                    workedMinutes = shift.workedMinutes,
                    breakMinutes = breakMinutes,
                    reconstructed = shift.wasIncomplete,
                    corrected = shift.id in correctedShifts
                )
            }

        return MonthlyShiftSummary(
            employeeId = employeeId,
            year = year,
            month = month,
            contractType = contractType,
            // Open or INCOMPLETE days contribute nothing, because their worked
            // time is not yet known. Counting them as zero would understate the
            // month silently; they are visible in `days` with a null, so the
            // reader can see there is something unresolved rather than a total
            // that quietly disagrees with the days above it.
            totalWorkedMinutes = days.sumOf { it.workedMinutes ?: 0 },
            days = days
        )
    }
}
