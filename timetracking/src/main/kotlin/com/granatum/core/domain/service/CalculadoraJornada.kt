package com.granatum.core.domain.service

import com.granatum.core.domain.exception.ValoresIncoherentesException
import java.time.Duration
import java.time.Instant

/**
 * Pure computation of worked time. No Spring, no JPA, no database - so the
 * cases that actually matter (midnight, the DST change, pausas longer than the
 * shift) can be tested without a context.
 *
 * Works on [Instant]s throughout, which is what makes the result correct across
 * the clock change: the difference between two instants is always real elapsed
 * time. With zone-less local times, the night the clocks go back would measure
 * an hour short and the legal record would be wrong.
 */
object CalculadoraJornada {

    /** A break, reduced to the two fields the computation needs. */
    data class IntervaloPausa(val inicio: Instant, val fin: Instant)

    /**
     * Worked minutes: `(salida - entrada) - sum(pausas)`.
     *
     * Returns whole minutes rather than decimal hours. The register is kept to
     * the minute, and an integer removes any chance of rounding drift when a
     * month of days is summed for a report or a CSV.
     *
     * @throws ValoresIncoherentesException if the values could not describe a
     *   real shift. Rejecting is deliberate: silently clamping a negative
     *   result to zero would hide incoherent data in a record that has to stand
     *   up to inspection.
     */
    fun minutosTrabajados(
        entrada: Instant,
        salida: Instant,
        pausas: List<IntervaloPausa>
    ): Int {
        if (!salida.isAfter(entrada)) {
            throw ValoresIncoherentesException("la salida no es posterior a la entrada")
        }

        pausas.forEach { pausa ->
            if (!pausa.fin.isAfter(pausa.inicio)) {
                throw ValoresIncoherentesException("una pausa termina antes de empezar")
            }
            if (pausa.inicio.isBefore(entrada) || pausa.fin.isAfter(salida)) {
                throw ValoresIncoherentesException("una pausa cae fuera de la jornada")
            }
        }

        comprobarSinSolapamiento(pausas)

        val jornada = Duration.between(entrada, salida)
        val descanso = pausas.fold(Duration.ZERO) { total, p ->
            total + Duration.between(p.inicio, p.fin)
        }
        val trabajado = jornada - descanso

        // Unreachable as the checks above stand: breaks that neither overlap
        // nor fall outside [entrada, salida] can sum at most the length of the
        // shift, so the worst case is exactly zero worked minutes - which is a
        // legitimate answer, not an error.
        //
        // Kept anyway, and documented as unreachable rather than quietly left
        // in: it costs one comparison and it is the invariant that would catch
        // a future change to the validation order above. A test cannot cover
        // it, which is precisely why it needs saying here.
        if (trabajado.isNegative) {
            throw ValoresIncoherentesException("las pausas suman mas que la jornada")
        }

        return trabajado.toMinutes().toInt()
    }

    /**
     * Rejects overlapping breaks.
     *
     * The live path cannot produce them - a partial unique index allows only
     * one open pausa per fichaje - so this guards the other way in: approving a
     * correction that adds or moves pausas. Sorting first makes it a single
     * pass instead of comparing every pair.
     */
    fun comprobarSinSolapamiento(pausas: List<IntervaloPausa>) {
        pausas.sortedBy { it.inicio }
            .zipWithNext()
            .forEach { (anterior, siguiente) ->
                if (siguiente.inicio.isBefore(anterior.fin)) {
                    throw ValoresIncoherentesException("hay pausas solapadas")
                }
            }
    }
}
