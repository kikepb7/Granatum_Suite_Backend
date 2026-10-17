package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.MaterialHistoryModel
import com.granatum.core.infrastructure.database.entities.MaterialHistoryEntity

fun MaterialHistoryEntity.toModel(): MaterialHistoryModel = MaterialHistoryModel(
    id = id,
    materialId = materialId,
    userId = userId,
    changeType = changeType,
    previousValue = previousValue,
    newValue = newValue,
    reason = reason,
    date = date
)
