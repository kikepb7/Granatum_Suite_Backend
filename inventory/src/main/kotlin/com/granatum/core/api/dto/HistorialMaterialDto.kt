package com.granatum.core.api.dto

import com.granatum.core.domain.type.TipoCambioHistorial
import java.time.Instant
import java.util.UUID

data class HistorialMaterialDto(
    val id: UUID,
    val materialId: UUID,
    val usuarioId: UUID,
    val tipoCambio: TipoCambioHistorial,
    val valorAnterior: String?,
    val valorNuevo: String?,
    val motivo: String,
    val fecha: Instant
)
