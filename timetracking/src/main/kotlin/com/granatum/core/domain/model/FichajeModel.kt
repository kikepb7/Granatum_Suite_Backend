package com.granatum.core.domain.model

import com.granatum.core.domain.type.EstadoFichaje
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Domain view of a working day: independent of JPA and of HTTP. Services return
 * this; controllers map it to a DTO and never see the entity.
 */
data class FichajeModel(
    val id: UUID,
    val empleadoId: UUID,
    val entrada: Instant,
    val salida: Instant?,
    val estado: EstadoFichaje,
    val minutosTrabajados: Int?,
    val fueIncompleto: Boolean,
    val ubicacionEntrada: UbicacionModel?,
    val ubicacionSalida: UbicacionModel?,
    val pausas: List<PausaModel>
)

data class UbicacionModel(
    val latitud: BigDecimal,
    val longitud: BigDecimal,
    val precisionMetros: Int?
)
