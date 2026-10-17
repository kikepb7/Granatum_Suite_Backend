package com.granatum.core.api.mappers

import com.granatum.core.api.dto.MaterialHistoryDto
import com.granatum.core.domain.model.MaterialHistoryModel

fun MaterialHistoryModel.toDto(): MaterialHistoryDto = MaterialHistoryDto(
    id = id,
    materialId = materialId,
    userId = userId,
    changeType = changeType,
    previousValue = previousValue,
    newValue = newValue,
    reason = reason,
    date = date
)
