package com.granatum.core.api.dto

import com.granatum.core.domain.type.EstadoSolicitud
import com.granatum.core.domain.type.TipoPausa
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CrearCorreccionRequest(
    /**
     * Mandatory, 10-500 characters. The lower bound is deliberate: a one-word
     * reason is indistinguishable from none, and the traceability the law wants
     * is the *why*, not the fact that a field was filled.
     */
    @field:NotBlank
    @field:Size(min = 10, max = 500)
    val motivo: String,

    @field:NotNull
    @field:Valid
    val valoresPropuestos: ValoresFichajeDto
)

/**
 * Proposed final state of the shift.
 *
 * **Has no `ubicacion` field, by design.** A request carrying one is rejected
 * with `UBICACION_NO_CORREGIBLE` rather than ignored, so a client never
 * believes it changed something it did not.
 */
data class ValoresFichajeDto(
    @field:NotNull val entrada: Instant,
    @field:NotNull val salida: Instant,
    @field:Valid val pausas: List<ValoresPausaDto> = emptyList()
)

data class ValoresPausaDto(
    @field:NotNull val tipo: TipoPausa,
    @field:NotNull val inicio: Instant,
    @field:NotNull val fin: Instant
)

data class RechazarCorreccionRequest(
    /**
     * Mandatory. A refusal with no reason is useless to the person who receives
     * it and leaves no record of the approver's criterion.
     */
    @field:NotBlank
    @field:Size(min = 10, max = 500)
    val motivoResolucion: String
)

data class CorreccionDto(
    val id: UUID,
    val fichajeId: UUID,
    val solicitanteId: UUID,
    val motivo: String,
    val estado: EstadoSolicitud,
    val valoresPropuestos: ValoresFichajeDto,
    val valoresOriginales: ValoresFichajeDto? = null,
    val resueltaPorId: UUID? = null,
    val resueltaEn: Instant? = null,
    val motivoResolucion: String? = null,
    val creadaEn: Instant
)
