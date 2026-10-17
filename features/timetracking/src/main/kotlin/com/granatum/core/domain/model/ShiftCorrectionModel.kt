package com.granatum.core.domain.model

import com.granatum.core.domain.type.CorrectionStatus
import java.time.Instant
import java.util.UUID

data class ShiftCorrectionModel(
    val id: UUID,
    val shiftId: UUID,
    val requesterId: UUID,
    val reason: String,
    val status: CorrectionStatus,
    val proposedValues: ShiftValues,
    /** Only present once approved: the state immediately before applying. */
    val originalValues: ShiftValues?,
    val resolvedById: UUID?,
    val resolvedAt: Instant?,
    val resolutionReason: String?,
    val createdAt: Instant
)
