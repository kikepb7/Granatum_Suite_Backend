package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.CategoriaModel
import com.granatum.core.infrastructure.database.entities.CategoriaEntity

fun CategoriaEntity.toModel(): CategoriaModel = CategoriaModel(
    id = id,
    nombre = nombre,
    descripcion = descripcion,
    createdAt = createdAt,
    updatedAt = updatedAt
)
