package com.granatum.core.api.dto

import com.granatum.core.domain.type.CorrectionStatus
import com.granatum.core.domain.type.BreakType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateShiftCorrectionRequest(
    /**
     * Mandatory, 10-500 characters. The lower bound is deliberate: a one-word
     * reason is indistinguishable from none, and the traceability the law wants
     * is the *why*, not the fact that a field was filled.
     */
    @field:NotBlank
    @field:Size(min = 10, max = 500)
    val reason: String,

    @field:NotNull
    @field:Valid
    val proposedValues: ShiftValuesDto
)

/**
 * Proposed final state of the shift.
 *
 * [location] exists **only so its presence can be refused**. A correction may
 * not change where someone clocked in (FR-020a): that is evidence, and an
 * editable location is not evidence of anything.
 *
 * Declaring the field is what makes the refusal possible. Leaving it out looked
 * cleaner and was wrong: Jackson ignores unknown properties by default, so a
 * request carrying `location` would have been accepted silently and the client
 * would believe it changed something it did not - the exact failure the
 * contract rules out.
 */
data class ShiftValuesDto(
    @field:NotNull val clockIn: Instant,
    @field:NotNull val clockOut: Instant,
    @field:Valid val breaks: List<BreakValuesDto> = emptyList(),
    val location: ShiftLocationDto? = null
)

data class BreakValuesDto(
    @field:NotNull val type: BreakType,
    @field:NotNull val start: Instant,
    @field:NotNull val end: Instant
)

data class RejectShiftCorrectionRequest(
    /**
     * Mandatory. A refusal with no reason is useless to the person who receives
     * it and leaves no record of the approver's criterion.
     */
    @field:NotBlank
    @field:Size(min = 10, max = 500)
    val resolutionReason: String
)

data class ShiftCorrectionDto(
    val id: UUID,
    val shiftId: UUID,
    val requesterId: UUID,
    val reason: String,
    val status: CorrectionStatus,
    val proposedValues: ShiftValuesDto,
    val originalValues: ShiftValuesDto? = null,
    val resolvedById: UUID? = null,
    val resolvedAt: Instant? = null,
    val resolutionReason: String? = null,
    val createdAt: Instant
)
