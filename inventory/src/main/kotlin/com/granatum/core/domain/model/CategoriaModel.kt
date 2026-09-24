package com.granatum.core.domain.model

import com.granatum.core.domain.type.EntityId
import java.time.Instant

data class CategoriaModel(
    val id: EntityId,
    val nombre: String,
    val descripcion: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
