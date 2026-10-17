package com.granatum.core.api.mappers

import com.granatum.core.api.dto.MaterialDimensionsDto
import com.granatum.core.api.dto.MaterialDimensionsRequest
import com.granatum.core.api.dto.MaterialDto
import com.granatum.core.api.dto.MaterialLocationDto
import com.granatum.core.api.dto.MaterialLocationRequest
import com.granatum.core.domain.model.MaterialDimensionsModel
import com.granatum.core.domain.model.MaterialLocation
import com.granatum.core.domain.model.MaterialModel
import com.granatum.core.domain.type.Warehouse

fun MaterialModel.toDto(): MaterialDto = MaterialDto(
    id = id,
    category = category.toDto(),
    description = description,
    dimensions = MaterialDimensionsDto(height = dimensions.height, width = dimensions.width),
    quantity = quantity,
    location = MaterialLocationDto(warehouse = location.warehouse, section = location.section),
    imageUrl = imageUrl,
    createdAt = createdAt,
    updatedAt = updatedAt
)

// The `!!` below are safe: these requests reach the mappers only after @Valid
// has checked @NotNull on every one of these fields.

fun MaterialDimensionsRequest.toModel(): MaterialDimensionsModel = MaterialDimensionsModel(height = height!!, width = width!!)

fun MaterialLocationRequest.toModel(): MaterialLocation =
    MaterialLocation(warehouse = Warehouse.valueOf(warehouse!!), section = section!!)
