package com.granatum.core.domain.model

import com.granatum.core.domain.type.EstadoSolicitud
import java.time.Instant
import java.util.UUID

data class SolicitudCorreccionModel(
    val id: UUID,
    val fichajeId: UUID,
    val solicitanteId: UUID,
    val motivo: String,
    val estado: EstadoSolicitud,
    val valoresPropuestos: ValoresFichaje,
    /** Only present once approved: the state immediately before applying. */
    val valoresOriginales: ValoresFichaje?,
    val resueltaPorId: UUID?,
    val resueltaEn: Instant?,
    val motivoResolucion: String?,
    val creadaEn: Instant
)
