package com.granatum.core.domain.model

import com.granatum.core.domain.type.BreakType
import java.time.Instant

/**
 * The complete final state of a working day, as a correction proposes it or as
 * it was before one was applied.
 *
 * A **whole state** rather than a patch: `breaks` replaces the existing list,
 * which lets one model cover adding a break that was never recorded, removing
 * one logged by mistake, and moving an existing one's times.
 *
 * Carries no location, on purpose. A correction may not change where someone
 * clocked in (FR-020a): that is evidence, and an editable location is not
 * evidence of anything.
 */
data class ShiftValues(
    val clockIn: Instant,
    val clockOut: Instant,
    val breaks: List<BreakValues>
)

data class BreakValues(
    val type: BreakType,
    val start: Instant,
    val end: Instant
)
