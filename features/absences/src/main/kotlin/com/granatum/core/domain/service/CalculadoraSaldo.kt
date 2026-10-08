package com.granatum.core.domain.service

import com.granatum.core.domain.model.Ausencia
import com.granatum.core.domain.model.EstadoAusencia
import com.granatum.core.domain.model.SaldoVacaciones
import com.granatum.core.domain.model.TipoAusencia
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Holiday balance, pure (feature 007, FR-014 to FR-017, research.md D-001).
 *
 * Calendar days of each holiday request that fall inside the year: 28 Dec - 4
 * Jan is 4 days of one year and 4 of the next. Only VACACIONES counts; leave and
 * sick leave never do. Cancelled and rejected requests give their days back.
 */
object CalculadoraSaldo {

    fun saldo(anio: Int, derecho: Int, ausencias: Collection<Ausencia>): SaldoVacaciones {
        val vacaciones = ausencias.filter { it.tipo == TipoAusencia.VACACIONES }
        return SaldoVacaciones(
            anio = anio,
            derecho = derecho,
            aprobados = vacaciones.filter { it.estado == EstadoAusencia.APROBADA }.sumOf { diasEnAnio(it.desde, it.hasta!!, anio) },
            pendientes = vacaciones.filter { it.estado == EstadoAusencia.PENDIENTE }.sumOf { diasEnAnio(it.desde, it.hasta!!, anio) }
        )
    }

    /** Days of the inclusive range [desde, hasta] that fall in [anio]. */
    fun diasEnAnio(desde: LocalDate, hasta: LocalDate, anio: Int): Int {
        val inicio = maxOf(desde, LocalDate.of(anio, 1, 1))
        val fin = minOf(hasta, LocalDate.of(anio, 12, 31))
        return if (fin.isBefore(inicio)) 0 else ChronoUnit.DAYS.between(inicio, fin).toInt() + 1
    }

    /** The years an inclusive range touches - at most two, since a range is at most 366 days. */
    fun anios(desde: LocalDate, hasta: LocalDate): IntRange = desde.year..hasta.year
}
