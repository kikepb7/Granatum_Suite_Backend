package com.granatum.core.domain.model

import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.domain.type.TipoPausa
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One line of an exported register: a fichaje with everything a reader needs
 * to understand it without a second file (FR-002 to FR-006).
 *
 * Carries no location, for anyone (FR-007): the export is the register of
 * working time, not of whereabouts. [documento] is null when the requester may
 * not see it (D-012); the writer then omits the column rather than leaving it
 * blank.
 */
data class FilaRegistro(
    val persona: String,
    val documento: String?,
    val puesto: String,
    val fecha: LocalDate,
    val entrada: Instant,
    val salida: Instant?,
    val pausas: List<TramoPausa>,
    /** Null while open or incomplete. Never a zero that would claim "worked nothing" (FR-006). */
    val minutosTrabajados: Int?,
    val estado: EstadoFichaje,
    /** The exit was added after the fact: `fueIncompleto` on the fichaje. */
    val completadoAPosteriori: Boolean,
    val corregido: Boolean,
    /** What was clocked before the first approved correction; null if never corrected (D-010). */
    val original: ValoresOriginales?,
    /** Approved corrections, oldest first. Pending and rejected ones are not applied, so not here. */
    val correcciones: List<CorreccionAplicada>
) {
    // Name and identity document stay out: Spring and the logs call toString on
    // whatever they hold, and that is how feature 002 leaked passwords (VI).
    override fun toString(): String =
        "FilaRegistro(fecha=$fecha, estado=$estado, corregido=$corregido)"
}

/** A break as exported: kind and times, no id. */
data class TramoPausa(
    val tipo: TipoPausa,
    val inicio: Instant,
    val fin: Instant?
)

/**
 * The fichaje's values immediately before its first approved correction, which
 * are the ones actually clocked. Later approvals store values that were already
 * corrected, so only the first one tells the truth about the original (D-010).
 *
 * [salida] is nullable because the original of a reconstructed day had none.
 */
data class ValoresOriginales(
    val entrada: Instant,
    val salida: Instant?,
    val pausas: List<TramoPausa>
)

/**
 * Who asked for a correction and who approved it, by name. The reason is not
 * exported: the spec does not ask for it, and it is free text that can hold any
 * personal detail (D-010).
 */
data class CorreccionAplicada(
    val resueltaEn: Instant,
    val solicitante: String,
    val aprobador: String
) {
    override fun toString(): String = "CorreccionAplicada(resueltaEn=$resueltaEn)"
}

/** What an export covers. */
sealed interface AlcanceExportacion {
    data class Persona(val empleadoId: UUID) : AlcanceExportacion
    data object Plantilla : AlcanceExportacion
    data class Mensual(val empleadoId: UUID, val anio: Int, val mes: Int) : AlcanceExportacion
}

/**
 * The range actually exported, after trimming what the retention period has
 * already purged or is about to (D-009). [recortado] says whether the request
 * asked for more than this, so the response can say so instead of handing over
 * a silently shorter file.
 */
data class RangoEfectivo(
    val desde: LocalDate,
    val hasta: LocalDate,
    val recortado: Boolean
)
