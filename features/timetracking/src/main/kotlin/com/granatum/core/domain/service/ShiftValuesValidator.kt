package com.granatum.core.domain.service

import com.granatum.core.domain.model.ShiftValues
import com.granatum.core.domain.service.WorkedTimeCalculator.BreakInterval

/**
 * Applies to proposed values the **same coherence rules as a live shift**
 * (FR-020b).
 *
 * Reusing [WorkedTimeCalculator] rather than re-implementing the checks is the
 * point: if the two drifted apart, a correction could be approved into a state
 * that the normal path would have refused, and the register would hold
 * something impossible.
 *
 * Pure, so it can be exercised without a database, and called twice - when the
 * request is made and again when it is approved, because the fichaje may have
 * changed in between through another correction.
 */
object ShiftValuesValidator {

    /**
     * @return the worked minutes the proposal implies, so the caller does not
     *   have to compute them a second time.
     * @throws com.granatum.core.domain.exception.InconsistentValuesException
     *   if the values could not describe a real shift.
     */
    fun validate(values: ShiftValues): Int =
        WorkedTimeCalculator.workedMinutes(
            clockIn = values.clockIn,
            clockOut = values.clockOut,
            breaks = values.breaks.map { BreakInterval(it.start, it.end) }
        )
}
