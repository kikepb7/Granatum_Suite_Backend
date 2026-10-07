package com.granatum.core.api.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Converts the civil dates clients send (`desde=2026-10-25`) into the absolute
 * instant range they mean.
 *
 * Instants are stored in UTC so that the difference between two of them is
 * always real elapsed time. The delicate part is not storage but **querying**:
 * `2026-10-25` is a Spanish civil date, and the day the clocks change has 23 or
 * 25 hours. Computing the range with a fixed offset would produce the wrong
 * window on exactly those days.
 *
 * `atStartOfDay(zone)` resolves this correctly, including the gap and the
 * overlap introduced by the DST transition.
 *
 * Lives in this module rather than in `common`: only `timetracking` uses it
 * today, and promoting it would make it shared surface with `inventory` for no
 * consumer (principle I). If the export feature needs it, it moves then.
 */
object RangoFechas {

    val ZONA: ZoneId = ZoneId.of("Europe/Madrid")

    /** Start of [fecha] in Europe/Madrid, inclusive. */
    fun inicioDelDia(fecha: LocalDate): Instant =
        fecha.atStartOfDay(ZONA).toInstant()

    /**
     * End of [fecha] in Europe/Madrid, **exclusive**: the start of the next
     * day. Exclusive rather than "23:59:59.999" so no instant in the final
     * fraction of a second can fall outside the range.
     */
    fun finDelDiaExclusivo(fecha: LocalDate): Instant =
        fecha.plusDays(1).atStartOfDay(ZONA).toInstant()

    /**
     * The range `[desde, hasta]` with both dates inclusive, expressed as a
     * half-open instant interval.
     */
    fun rango(desde: LocalDate, hasta: LocalDate): Pair<Instant, Instant> =
        inicioDelDia(desde) to finDelDiaExclusivo(hasta)

    /**
     * The civil date a given instant belongs to in Europe/Madrid. A fichaje is
     * attributed to the civil date of its entry, so at 00:30 Madrid time in
     * summer it belongs to today even though it is still yesterday in UTC.
     */
    fun fechaCivil(instante: Instant): LocalDate =
        instante.atZone(ZONA).toLocalDate()
}
