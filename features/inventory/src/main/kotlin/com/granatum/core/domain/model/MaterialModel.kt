package com.granatum.core.domain.model

import com.granatum.core.domain.type.EntityId
import java.time.Instant

data class MaterialModel(
    val id: EntityId,
    val category: CategoryModel,
    val description: String,
    val dimensions: MaterialDimensionsModel,
    val quantity: Int,
    val location: MaterialLocation,
    /** Path of the image in the bucket; never leaves through HTTP. */
    val imagePath: String?,
    /**
     * Temporary signed URL for the image, filled in by the service when it
     * reads (research.md D-005). Null without an image, or when it could not
     * be signed.
     */
    val imageUrl: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)
