package com.granatum.core.domain.model

import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.TipoCambioHistorial
import java.time.Instant

data class HistorialMaterialModel(
    val id: EntityId,
    val materialId: EntityId,
    val usuarioId: EntityId,
    val tipoCambio: TipoCambioHistorial,
    val valorAnterior: String?,
    val valorNuevo: String?,
    val motivo: String,
    val fecha: Instant
)
