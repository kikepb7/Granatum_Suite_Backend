package com.granatum.core.api.mappers

import com.granatum.core.api.dto.CategoriaDto
import com.granatum.core.domain.model.CategoriaModel

fun CategoriaModel.toDto(): CategoriaDto = CategoriaDto(
    id = id,
    nombre = nombre,
    descripcion = descripcion,
    createdAt = createdAt,
    updatedAt = updatedAt
)
