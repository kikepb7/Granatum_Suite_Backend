package com.granatum.core.api.mappers

import com.granatum.core.api.dto.HistorialMaterialDto
import com.granatum.core.domain.model.HistorialMaterialModel

fun HistorialMaterialModel.toDto(): HistorialMaterialDto = HistorialMaterialDto(
    id = id,
    materialId = materialId,
    usuarioId = usuarioId,
    tipoCambio = tipoCambio,
    valorAnterior = valorAnterior,
    valorNuevo = valorNuevo,
    motivo = motivo,
    fecha = fecha
)
