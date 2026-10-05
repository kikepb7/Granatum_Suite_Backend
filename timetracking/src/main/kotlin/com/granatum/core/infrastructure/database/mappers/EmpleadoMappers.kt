package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.EmpleadoModel
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity

fun EmpleadoEntity.toModel(): EmpleadoModel = EmpleadoModel(
    id = id,
    nombre = nombre,
    documentoIdentidad = documentoIdentidad,
    puesto = puesto,
    tipoContrato = tipoContrato,
    fechaAlta = fechaAlta,
    activo = activo
)
