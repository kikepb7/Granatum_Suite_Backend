package com.granatum.core.api.dto

import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.domain.type.TipoPausa
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Fields every fichaje operation carries.
 *
 * `clientEventId` is the idempotency key the client generates; `occurredAt` is
 * when the operation actually happened per the device, and is what the
 * working-time computation uses. The server records its own arrival time
 * separately and never computes with it.
 */
interface OperacionFichajeRequest {
    val clientEventId: UUID
    val occurredAt: Instant
}

data class EntradaRequest(
    @field:NotNull override val clientEventId: UUID,
    @field:NotNull override val occurredAt: Instant,
    /**
     * Optional, and its absence is never an error: a denied location permission
     * or no GPS signal must not stop someone recording their shift. Blocking it
     * would breach the legal duty to record working time for an incidental
     * reason, and geofencing is out of scope.
     */
    @field:Valid val ubicacion: UbicacionDto? = null
) : OperacionFichajeRequest

data class InicioPausaRequest(
    @field:NotNull override val clientEventId: UUID,
    @field:NotNull override val occurredAt: Instant,
    @field:NotNull val tipo: TipoPausa
) : OperacionFichajeRequest

/** No pausa id: only one may be open at a time, so it is unambiguous. */
data class FinPausaRequest(
    @field:NotNull override val clientEventId: UUID,
    @field:NotNull override val occurredAt: Instant
) : OperacionFichajeRequest

data class SalidaRequest(
    @field:NotNull override val clientEventId: UUID,
    @field:NotNull override val occurredAt: Instant,
    @field:Valid val ubicacion: UbicacionDto? = null
) : OperacionFichajeRequest

data class UbicacionDto(
    @field:NotNull val latitud: BigDecimal,
    @field:NotNull val longitud: BigDecimal,
    val precisionMetros: Int? = null
)

/**
 * Response shape.
 *
 * Carries no document number and no internal auditing fields. The location
 * fields are nullable because a fichaje may genuinely have none - but for a
 * REPRESENTANTE they are **omitted** rather than nulled, which is why
 * serialisation of nulls is suppressed: null would be indistinguishable from
 * "recorded without a location" and would misrepresent the register.
 */
data class FichajeDto(
    val id: UUID,
    val empleadoId: UUID,
    val entrada: Instant,
    val salida: Instant?,
    val estado: EstadoFichaje,
    val minutosTrabajados: Int?,
    val fueIncompleto: Boolean,
    val ubicacionEntrada: UbicacionDto? = null,
    val ubicacionSalida: UbicacionDto? = null,
    val pausas: List<PausaDto> = emptyList()
)

data class PausaDto(
    val id: UUID,
    val tipo: TipoPausa,
    val inicio: Instant,
    val fin: Instant?
)
