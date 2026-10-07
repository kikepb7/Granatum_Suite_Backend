package com.granatum.core.service

import com.granatum.core.api.util.RangoFechas
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.Period

/**
 * The legal retention boundary of the working-time register: the **only** place
 * it is computed.
 *
 * Two features need it. The retention purge (feature 001) deletes what is older
 * than it; the export (feature 003) tells the reader from which date a file can
 * still hold data, so an empty file is not read as "worked nothing" (FR-017). It
 * used to live inside `DepuracionRetencionJob`; had the export computed its own
 * copy, changing the period in one place and not the other would have the export
 * promising data already destroyed, or hiding data that still exists.
 *
 * Owns `timetracking.retencion.anios` for the same reason: one property, one
 * reader.
 */
@Component
class PlazoConservacion(
    @param:Value("\${timetracking.retencion.anios:4}") private val anios: Int,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * The first civil date that may still hold records. Anything whose entry is
     * before the start of this day has completed its period in full.
     *
     * "Today" is the civil date in Madrid, not in UTC: around midnight they
     * differ, and a UTC date would move the boundary a day early - shortening
     * the legal period for an hour or two every night.
     */
    fun fechaCorte(): LocalDate =
        LocalDate.now(clock.withZone(RangoFechas.ZONA)).minus(Period.ofYears(anios))
}
