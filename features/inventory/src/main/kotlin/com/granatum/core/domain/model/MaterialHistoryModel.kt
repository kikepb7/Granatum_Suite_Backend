package com.granatum.core.domain.model

import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.MaterialChangeType
import java.time.Instant

data class MaterialHistoryModel(
    val id: EntityId,
    val materialId: EntityId,
    val userId: EntityId,
    val changeType: MaterialChangeType,
    val previousValue: String?,
    val newValue: String?,
    val reason: String,
    val date: Instant
)
