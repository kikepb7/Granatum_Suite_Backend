package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.HistorialMaterialModel
import com.granatum.core.infrastructure.database.entities.HistorialMaterialEntity

fun HistorialMaterialEntity.toModel(): HistorialMaterialModel = HistorialMaterialModel(
    id = id,
    materialId = materialId,
    usuarioId = usuarioId,
    tipoCambio = tipoCambio,
    valorAnterior = valorAnterior,
    valorNuevo = valorNuevo,
    motivo = motivo,
    fecha = fecha
)
