package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.EventMaterialModel
import com.granatum.core.domain.model.EventModel
import com.granatum.core.infrastructure.database.entities.EventEntity
import com.granatum.core.infrastructure.database.entities.EventMaterialEntity

fun EventEntity.toModel(materials: List<EventMaterialEntity>) = EventModel(
    id = id,
    name = name,
    description = description,
    date = date,
    status = status,
    materials = materials.map { it.toModel() }.sortedWith(compareBy({ it.category.lowercase() }, { it.description.lowercase() })),
    createdBy = createdBy,
    finishedAt = finishedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun EventMaterialEntity.toModel() = EventMaterialModel(
    materialId = materialId,
    category = category,
    description = description,
    quantity = quantity
)
