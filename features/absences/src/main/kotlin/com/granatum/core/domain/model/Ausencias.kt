package com.granatum.core.domain.model

import com.granatum.core.domain.type.EntityId
import java.time.Instant
import java.time.LocalDate

/** Feature 007. Domain model of absences: no JPA, no HTTP. */
enum class TipoAusencia {
    VACACIONES,

    /** Paid leave (art. 37.3 of the Workers' Statute); needs a [CausaPermiso]. */
    PERMISO,

    /** Registered by ENCARGADO or ADMIN; no free text, ever (FR-007). */
    BAJA_MEDICA
}

enum class CausaPermiso {
    MATRIMONIO,
    NACIMIENTO,
    FALLECIMIENTO_FAMILIAR,
    ENFERMEDAD_FAMILIAR,
    MUDANZA,
    DEBER_INEXCUSABLE,
    OTRO
}

enum class EstadoAusencia { PENDIENTE, APROBADA, RECHAZADA, CANCELADA }

/**
 * An absence. `hasta` is null only for an open sick leave.
 *
 * `toString` leaves out the comment and the rejection reason: free text a
 * person wrote, which may say more than it should (FR-021).
 */
data class Ausencia(
    val id: EntityId,
    val empleadoId: EntityId,
    val tipo: TipoAusencia,
    val causa: CausaPermiso?,
    val desde: LocalDate,
    val hasta: LocalDate?,
    val estado: EstadoAusencia,
    val comentario: String?,
    val motivoRechazo: String?,
    val solicitadaPor: EntityId,
    val solicitadaEn: Instant,
    val resueltaPor: EntityId?,
    val resueltaEn: Instant?,
    val canceladaEn: Instant?,
    val version: Int
) {
    /** Whether it still holds its days: pending or approved. */
    val vigente: Boolean get() = estado == EstadoAusencia.PENDIENTE || estado == EstadoAusencia.APROBADA

    override fun toString(): String =
        "Ausencia(id=$id, empleadoId=$empleadoId, tipo=$tipo, desde=$desde, hasta=$hasta, estado=$estado)"
}

/** A person's holidays in one year, in calendar days (FR-015). */
data class SaldoVacaciones(
    val anio: Int,
    val derecho: Int,
    val aprobados: Int,
    val pendientes: Int
) {
    val disponibles: Int get() = derecho - aprobados - pendientes
}
