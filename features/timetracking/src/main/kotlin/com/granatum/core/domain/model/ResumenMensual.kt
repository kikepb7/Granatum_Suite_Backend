package com.granatum.core.domain.model

import com.granatum.core.domain.type.TipoContrato
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A month of someone's register, aggregated.
 *
 * The **calculation** lives in this module because it applies its domain rules:
 * what counts as worked time, how approved corrections affect it, which days
 * were reconstructed. The **delivery** - download, file format, sending cadence
 * - belongs to the export feature. Putting CSV generation here would duplicate
 * what that feature owns; putting this aggregation there would force the export
 * module to know the shift rules, which is the worse of the two couplings.
 */
data class ResumenMensual(
    val empleadoId: UUID,
    val anio: Int,
    val mes: Int,
    /**
     * Carried because article 12.4.c obliges an employer to hand part-time
     * staff a monthly summary with their payslip, so the consumer needs to know
     * whether this month's summary is one of those.
     */
    val tipoContrato: TipoContrato,
    val totalMinutosTrabajados: Int,
    val dias: List<DiaResumen>
)

data class DiaResumen(
    val fecha: LocalDate,
    val entrada: Instant,
    val salida: Instant?,
    val minutosTrabajados: Int?,
    val minutosPausa: Int,
    /** The exit was added after the fact: `fueIncompleto` on the fichaje. */
    val reconstruido: Boolean,
    /** At least one approved correction touched this day. */
    val corregido: Boolean
)
