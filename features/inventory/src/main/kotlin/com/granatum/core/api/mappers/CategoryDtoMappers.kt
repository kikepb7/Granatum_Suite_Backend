package com.granatum.core.api.mappers

import com.granatum.core.api.dto.CategoryDto
import com.granatum.core.domain.model.CategoryModel

fun CategoryModel.toDto(): CategoryDto = CategoryDto(
    id = id,
    name = name,
    description = description,
    createdAt = createdAt,
    updatedAt = updatedAt
)
