package com.granatum.core.api.dto

import java.time.Instant
import java.util.UUID

data class CategoriaDto(
    val id: UUID,
    val nombre: String,
    val descripcion: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
