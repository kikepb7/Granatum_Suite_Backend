package com.granatum.core.domain.service

import com.granatum.core.domain.exception.InconsistentValuesException
import java.time.Duration
import java.time.Instant

/**
 * Pure computation of worked time. No Spring, no JPA, no database - so the
 * cases that actually matter (midnight, the DST change, pausas longer than the
 * shift) can be tested without a context.
 *
 * Works on [Instant]s throughout, which is what makes the result correct across
 * the clock change: the difference between two instants is always real elapsed
 * time. With zone-less local times, the night the clocks go back would measure
 * an hour short and the legal record would be wrong.
 */
object WorkedTimeCalculator {

    /** A break, reduced to the two fields the computation needs. */
    data class BreakInterval(val start: Instant, val end: Instant)

    /**
     * Worked minutes: `(clockOut - clockIn) - sum(breaks)`.
     *
     * Returns whole minutes rather than decimal hours. The register is kept to
     * the minute, and an integer removes any chance of rounding drift when a
     * month of days is summed for a report or a CSV.
     *
     * @throws InconsistentValuesException if the values could not describe a
     *   real shift. Rejecting is deliberate: silently clamping a negative
     *   result to zero would hide incoherent data in a record that has to stand
     *   up to inspection.
     */
    fun workedMinutes(
        clockIn: Instant,
        clockOut: Instant,
        breaks: List<BreakInterval>
    ): Int {
        if (!clockOut.isAfter(clockIn)) {
            throw InconsistentValuesException("la salida no es posterior a la entrada")
        }

        breaks.forEach { shiftBreak ->
            if (!shiftBreak.end.isAfter(shiftBreak.start)) {
                throw InconsistentValuesException("una pausa termina antes de empezar")
            }
            if (shiftBreak.start.isBefore(clockIn) || shiftBreak.end.isAfter(clockOut)) {
                throw InconsistentValuesException("una pausa cae fuera de la jornada")
            }
        }

        checkNoOverlap(breaks)

        val shiftLength = Duration.between(clockIn, clockOut)
        val breakTime = breaks.fold(Duration.ZERO) { total, p ->
            total + Duration.between(p.start, p.end)
        }
        val worked = shiftLength - breakTime

        // Unreachable as the checks above stand: breaks that neither overlap
        // nor fall outside [clockIn, clockOut] can sum at most the length of the
        // shift, so the worst case is exactly zero worked minutes - which is a
        // legitimate answer, not an error.
        //
        // Kept anyway, and documented as unreachable rather than quietly left
        // in: it costs one comparison and it is the invariant that would catch
        // a future change to the validation order above. A test cannot cover
        // it, which is precisely why it needs saying here.
        if (worked.isNegative) {
            throw InconsistentValuesException("las pausas suman mas que la jornada")
        }

        return worked.toMinutes().toInt()
    }

    /**
     * Rejects overlapping breaks.
     *
     * The live path cannot produce them - a partial unique index allows only
     * one open pausa per fichaje - so this guards the other way in: approving a
     * correction that adds or moves pausas. Sorting first makes it a single
     * pass instead of comparing every pair.
     */
    fun checkNoOverlap(breaks: List<BreakInterval>) {
        breaks.sortedBy { it.start }
            .zipWithNext()
            .forEach { (previous, next) ->
                if (next.start.isBefore(previous.end)) {
                    throw InconsistentValuesException("hay pausas solapadas")
                }
            }
    }
}
