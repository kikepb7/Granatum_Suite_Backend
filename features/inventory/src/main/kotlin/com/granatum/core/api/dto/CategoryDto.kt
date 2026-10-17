package com.granatum.core.api.dto

import java.time.Instant
import java.util.UUID

data class CategoryDto(
    val id: UUID,
    val name: String,
    val description: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
