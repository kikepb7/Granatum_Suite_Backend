package com.granatum.core.domain.model

import com.granatum.core.domain.type.ShiftStatus
import com.granatum.core.domain.type.BreakType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One line of an exported register: a fichaje with everything a reader needs
 * to understand it without a second file (FR-002 to FR-006).
 *
 * Carries no location, for anyone (FR-007): the export is the register of
 * working time, not of whereabouts. [document] is null when the requester may
 * not see it (D-012); the writer then omits the column rather than leaving it
 * blank.
 */
data class RegisterRow(
    val name: String,
    /** Null while pending (feature 012, FR-003): the cell is left empty, the name is not split. */
    val lastName: String?,
    val document: String?,
    val position: String,
    val date: LocalDate,
    val clockIn: Instant,
    val clockOut: Instant?,
    val breaks: List<BreakSpan>,
    /** Null while open or incomplete. Never a zero that would claim "worked nothing" (FR-006). */
    val workedMinutes: Int?,
    val status: ShiftStatus,
    /** The exit was added after the fact: `wasIncomplete` on the fichaje. */
    val completedAfterwards: Boolean,
    val corrected: Boolean,
    /** What was clocked before the first approved correction; null if never corrected (D-010). */
    val original: OriginalShiftValues?,
    /** Approved corrections, oldest first. Pending and rejected ones are not applied, so not here. */
    val corrections: List<AppliedCorrection>
) {
    // Name and identity document stay out: Spring and the logs call toString on
    // whatever they hold, and that is how feature 002 leaked passwords (VI).
    override fun toString(): String =
        "RegisterRow(date=$date, status=$status, corrected=$corrected)"
}

/** A break as exported: kind and times, no id. */
data class BreakSpan(
    val type: BreakType,
    val start: Instant,
    val end: Instant?
)

/**
 * The fichaje's values immediately before its first approved correction, which
 * are the ones actually clocked. Later approvals store values that were already
 * corrected, so only the first one tells the truth about the original (D-010).
 *
 * [clockOut] is nullable because the original of a reconstructed day had none.
 */
data class OriginalShiftValues(
    val clockIn: Instant,
    val clockOut: Instant?,
    val breaks: List<BreakSpan>
)

/**
 * Who asked for a correction and who approved it, by name. The reason is not
 * exported: the spec does not ask for it, and it is free text that can hold any
 * personal detail (D-010).
 */
data class AppliedCorrection(
    val resolvedAt: Instant,
    val requester: String,
    val approver: String
) {
    override fun toString(): String = "AppliedCorrection(resolvedAt=$resolvedAt)"
}

/** What an export covers. */
sealed interface ExportTarget {
    data class Person(val employeeId: UUID) : ExportTarget
    data object Staff : ExportTarget
    data class Monthly(val employeeId: UUID, val year: Int, val month: Int) : ExportTarget
}

/**
 * The range actually exported, after trimming what the retention period has
 * already purged or is about to (D-009). [trimmed] says whether the request
 * asked for more than this, so the response can say so instead of handing over
 * a silently shorter file.
 */
data class EffectiveRange(
    val from: LocalDate,
    val to: LocalDate,
    val trimmed: Boolean
)
