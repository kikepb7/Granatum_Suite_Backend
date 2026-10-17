package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.CategoryModel
import com.granatum.core.infrastructure.database.entities.CategoryEntity

fun CategoryEntity.toModel(): CategoryModel = CategoryModel(
    id = id,
    name = name,
    description = description,
    createdAt = createdAt,
    updatedAt = updatedAt
)
