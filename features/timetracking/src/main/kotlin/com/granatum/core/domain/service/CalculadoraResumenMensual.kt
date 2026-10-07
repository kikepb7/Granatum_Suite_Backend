package com.granatum.core.domain.service

import com.granatum.core.api.util.RangoFechas
import com.granatum.core.domain.model.DiaResumen
import com.granatum.core.domain.model.FichajeModel
import com.granatum.core.domain.model.ResumenMensual
import com.granatum.core.domain.type.TipoContrato
import java.time.Duration
import java.util.UUID

/**
 * Aggregates a month of shifts. Pure, so it can be tested without a database.
 *
 * Reflects the values **currently in force** after approved corrections, not
 * the originals (FR-034) - the summary is what the month looked like, and the
 * originals belong in the correction history where their authorship is
 * recorded alongside them.
 */
object CalculadoraResumenMensual {

    fun calcular(
        empleadoId: UUID,
        anio: Int,
        mes: Int,
        tipoContrato: TipoContrato,
        fichajes: List<FichajeModel>,
        fichajesCorregidos: Set<UUID>
    ): ResumenMensual {
        val dias = fichajes
            .sortedBy { it.entrada }
            .map { fichaje ->
                val minutosPausa = fichaje.pausas
                    .filter { it.fin != null }
                    .fold(Duration.ZERO) { total, p ->
                        total + Duration.between(p.inicio, p.fin)
                    }
                    .toMinutes()
                    .toInt()

                DiaResumen(
                    // The civil date of the entry, in Europe/Madrid: a shift
                    // starting at 00:30 Spanish summer time belongs to that day
                    // and not to the previous one, which is what UTC would say.
                    fecha = RangoFechas.fechaCivil(fichaje.entrada),
                    entrada = fichaje.entrada,
                    salida = fichaje.salida,
                    minutosTrabajados = fichaje.minutosTrabajados,
                    minutosPausa = minutosPausa,
                    reconstruido = fichaje.fueIncompleto,
                    corregido = fichaje.id in fichajesCorregidos
                )
            }

        return ResumenMensual(
            empleadoId = empleadoId,
            anio = anio,
            mes = mes,
            tipoContrato = tipoContrato,
            // Open or INCOMPLETO days contribute nothing, because their worked
            // time is not yet known. Counting them as zero would understate the
            // month silently; they are visible in `dias` with a null, so the
            // reader can see there is something unresolved rather than a total
            // that quietly disagrees with the days above it.
            totalMinutosTrabajados = dias.sumOf { it.minutosTrabajados ?: 0 },
            dias = dias
        )
    }
}
