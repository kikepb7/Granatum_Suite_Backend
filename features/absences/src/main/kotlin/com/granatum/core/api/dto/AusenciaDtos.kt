package com.granatum.core.api.dto

import com.granatum.core.domain.model.Ausencia
import com.granatum.core.domain.model.CausaPermiso
import com.granatum.core.domain.model.EstadoAusencia
import com.granatum.core.domain.model.SaldoVacaciones
import com.granatum.core.domain.model.TipoAusencia
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/*
 * Feature 007, specs/007-absences/contracts/README.md. Requests that carry a
 * comment or a reason override toString: free text a person wrote must not end
 * up in a log line (FR-021).
 */

data class SolicitudAusenciaRequest(
    @field:NotNull val tipo: TipoAusencia? = null,
    val causa: CausaPermiso? = null,
    @field:NotNull val desde: LocalDate? = null,
    @field:NotNull val hasta: LocalDate? = null,
    @field:Size(max = 500) val comentario: String? = null
) {
    override fun toString(): String = "SolicitudAusenciaRequest(tipo=$tipo, desde=$desde, hasta=$hasta)"
}

data class RegistroAusenciaRequest(
    @field:NotNull val empleadoId: UUID? = null,
    @field:NotNull val tipo: TipoAusencia? = null,
    val causa: CausaPermiso? = null,
    @field:NotNull val desde: LocalDate? = null,
    /** Only a sick leave may leave it empty: it is closed later with the discharge date. */
    val hasta: LocalDate? = null,
    @field:Size(max = 500) val comentario: String? = null
) {
    override fun toString(): String = "RegistroAusenciaRequest(empleadoId=$empleadoId, tipo=$tipo, desde=$desde, hasta=$hasta)"
}

data class RechazoAusenciaRequest(
    @field:NotBlank @field:Size(max = 500) val motivo: String = ""
) {
    override fun toString(): String = "RechazoAusenciaRequest(motivo=***)"
}

data class AltaBajaRequest(@field:NotNull val hasta: LocalDate? = null)

data class DerechoVacacionesRequest(@field:NotNull @field:Min(0) @field:Max(366) val dias: Int? = null)

data class AusenciaResponse(
    val id: UUID,
    val empleadoId: UUID,
    val tipo: TipoAusencia,
    val causa: CausaPermiso?,
    val desde: LocalDate,
    val hasta: LocalDate?,
    val estado: EstadoAusencia,
    val comentario: String?,
    val motivoRechazo: String?,
    val solicitadaPor: UUID,
    val solicitadaEn: Instant,
    val resueltaPor: UUID?,
    val resueltaEn: Instant?,
    val canceladaEn: Instant?
) {
    override fun toString(): String = "AusenciaResponse(id=$id, tipo=$tipo, estado=$estado)"

    companion object {
        fun de(a: Ausencia) = AusenciaResponse(
            a.id, a.empleadoId, a.tipo, a.causa, a.desde, a.hasta, a.estado, a.comentario, a.motivoRechazo,
            a.solicitadaPor, a.solicitadaEn, a.resueltaPor, a.resueltaEn, a.canceladaEn
        )
    }
}

data class SaldoVacacionesResponse(
    val empleadoId: UUID,
    val anio: Int,
    val derecho: Int,
    val aprobados: Int,
    val pendientes: Int,
    val disponibles: Int
) {
    companion object {
        fun de(empleadoId: UUID, s: SaldoVacaciones) =
            SaldoVacacionesResponse(empleadoId, s.anio, s.derecho, s.aprobados, s.pendientes, s.disponibles)
    }
}
