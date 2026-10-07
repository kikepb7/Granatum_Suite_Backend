package com.granatum.core

import com.granatum.core.service.PlazoConservacion
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

/**
 * The retention boundary, computed in exactly one place.
 *
 * Both the purge and the export use it: the purge to decide what to delete, the
 * export to say from which date a file can still hold data (FR-017). If they
 * computed it separately, changing the period in one and not the other would
 * have the export promising data already destroyed, or hiding data that still
 * exists.
 */
class PlazoConservacionTest {

    private val madrid = ZoneId.of("Europe/Madrid")

    private fun plazo(hoy: String, anios: Int = 4, hora: Int = 12) = PlazoConservacion(
        anios,
        Clock.fixed(ZonedDateTime.of(LocalDate.parse(hoy).atTime(hora, 0), madrid).toInstant(), madrid)
    )

    @Test
    fun `the cut-off is exactly the configured number of years ago`() {
        assertEquals(LocalDate.parse("2022-10-06"), plazo("2026-10-06").fechaCorte())
    }

    @Test
    fun `the period is configuration and not a constant`() {
        assertEquals(LocalDate.parse("2021-10-06"), plazo("2026-10-06", anios = 5).fechaCorte())
    }

    @Test
    fun `a leap day maps to a leap day four years earlier`() {
        assertEquals(LocalDate.parse("2024-02-29"), plazo("2028-02-29").fechaCorte())
    }

    /**
     * "Today" is the civil date in Madrid, not in UTC. At 00:30 on 7 October in
     * Madrid it is still 6 October in UTC, and a UTC date would move the
     * boundary a day early - shortening the legal retention period for an hour
     * or two every night.
     */
    @Test
    fun `today is the civil date in Madrid and not in UTC`() {
        val justoTrasMedianoche = PlazoConservacion(
            4,
            Clock.fixed(ZonedDateTime.of(2026, 10, 7, 0, 30, 0, 0, madrid).toInstant(), ZoneId.of("UTC"))
        )
        assertEquals(LocalDate.parse("2022-10-07"), justoTrasMedianoche.fechaCorte())
    }
}
