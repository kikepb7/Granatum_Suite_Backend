package com.granatum.core.api.dto

import com.granatum.core.domain.type.Warehouse
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class MaterialDto(
    val id: UUID,
    val category: CategoryDto,
    val description: String,
    val dimensions: MaterialDimensionsDto,
    val quantity: Int,
    val location: MaterialLocationDto,
    /** Temporary (FR-018). Null without an image, or when it could not be signed. */
    val imageUrl: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)

/** Centimetres. */
data class MaterialDimensionsDto(
    val height: BigDecimal,
    val width: BigDecimal
)

data class MaterialLocationDto(
    val warehouse: Warehouse,
    val section: Int
)
