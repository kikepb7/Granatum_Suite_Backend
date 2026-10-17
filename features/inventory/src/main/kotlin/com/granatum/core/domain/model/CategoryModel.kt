package com.granatum.core.domain.model

import com.granatum.core.domain.type.EntityId
import java.time.Instant

data class CategoryModel(
    val id: EntityId,
    val name: String,
    val description: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
