package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.MaterialDimensionsModel
import com.granatum.core.domain.model.MaterialLocation
import com.granatum.core.domain.model.MaterialModel
import com.granatum.core.infrastructure.database.entities.MaterialDimensionsEmbeddable
import com.granatum.core.infrastructure.database.entities.MaterialEntity
import com.granatum.core.infrastructure.database.entities.MaterialLocationEmbeddable

fun MaterialDimensionsEmbeddable.toModel(): MaterialDimensionsModel = MaterialDimensionsModel(height = height, width = width)

fun MaterialLocationEmbeddable.toModel(): MaterialLocation = MaterialLocation(warehouse = warehouse, section = section)

fun MaterialEntity.toModel(): MaterialModel = MaterialModel(
    id = id,
    category = category.toModel(),
    description = description,
    dimensions = dimensions.toModel(),
    quantity = quantity,
    location = location.toModel(),
    imagePath = imagePath,
    createdAt = createdAt,
    updatedAt = updatedAt
)
