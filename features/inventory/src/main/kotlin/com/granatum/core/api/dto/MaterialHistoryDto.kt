package com.granatum.core.api.dto

import com.granatum.core.domain.type.MaterialChangeType
import java.time.Instant
import java.util.UUID

data class MaterialHistoryDto(
    val id: UUID,
    val materialId: UUID,
    val userId: UUID,
    val changeType: MaterialChangeType,
    val previousValue: String?,
    val newValue: String?,
    val reason: String,
    val date: Instant
)
