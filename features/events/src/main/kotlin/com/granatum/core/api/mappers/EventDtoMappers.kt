package com.granatum.core.api.mappers

import com.granatum.core.api.dto.AvailableMaterialDto
import com.granatum.core.api.dto.EventDto
import com.granatum.core.api.dto.EventLocationDto
import com.granatum.core.api.dto.EventMaterialDto
import com.granatum.core.domain.contract.MaterialStock
import com.granatum.core.domain.model.EventMaterialModel
import com.granatum.core.domain.model.EventModel

fun EventModel.toDto() = EventDto(
    id = id,
    name = name,
    description = description,
    date = date,
    status = status,
    materials = materials.map { it.toDto() },
    createdBy = createdBy,
    finishedAt = finishedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun EventMaterialModel.toDto() = EventMaterialDto(
    materialId = materialId,
    category = category,
    description = description,
    quantity = quantity,
    location = if (warehouse != null && section != null) EventLocationDto(warehouse, section) else null,
    imageUrl = imageUrl
)

fun MaterialStock.toAvailableDto() = AvailableMaterialDto(
    id = id,
    category = category,
    description = description,
    quantity = quantity,
    location = EventLocationDto(warehouse, section),
    imageUrl = imageUrl
)
