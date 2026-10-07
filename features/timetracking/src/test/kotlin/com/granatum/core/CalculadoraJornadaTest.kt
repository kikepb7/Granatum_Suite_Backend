package com.granatum.core

import com.granatum.core.domain.exception.ValoresIncoherentesException
import com.granatum.core.domain.service.CalculadoraJornada
import com.granatum.core.domain.service.CalculadoraJornada.IntervaloPausa
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pure unit tests: no Spring, no database. The computation is isolated in
 * `domain/service` precisely so the cases that matter can be checked without a
 * context.
 */
class CalculadoraJornadaTest {

    private val madrid = ZoneId.of("Europe/Madrid")

    private fun madrid(fecha: String, hora: String) =
        LocalDateTime.of(LocalDate.parse(fecha), java.time.LocalTime.parse(hora))
            .atZone(madrid)
            .toInstant()

    @Test
    fun `a nine hour shift with two breaks totalling an hour is 480 minutes`() {
        val minutos = CalculadoraJornada.minutosTrabajados(
            entrada = madrid("2026-10-05", "07:00"),
            salida = madrid("2026-10-05", "16:00"),
            pausas = listOf(
                IntervaloPausa(madrid("2026-10-05", "09:00"), madrid("2026-10-05", "09:15")),
                IntervaloPausa(madrid("2026-10-05", "11:00"), madrid("2026-10-05", "11:45"))
            )
        )

        assertEquals(480, minutos)
    }

    @Test
    fun `a shift with no breaks counts the whole interval`() {
        val minutos = CalculadoraJornada.minutosTrabajados(
            entrada = madrid("2026-10-05", "07:00"),
            salida = madrid("2026-10-05", "15:00"),
            pausas = emptyList()
        )

        assertEquals(480, minutos)
    }

    @Test
    fun `a shift crossing midnight is one shift of real elapsed time`() {
        val minutos = CalculadoraJornada.minutosTrabajados(
            entrada = madrid("2026-10-07", "22:00"),
            salida = madrid("2026-10-08", "06:00"),
            pausas = emptyList()
        )

        assertEquals(480, minutos)
    }

    /**
     * The case that separates a correct implementation from one that looks
     * correct.
     *
     * On the night of 25 October 2026 the clocks in Spain go back an hour, so
     * between 00:00 and 08:00 *civil* time **nine** real hours elapse. A
     * computation over zone-less local times would answer 480 and under-record
     * an hour of someone's working day.
     */
    @Test
    fun `a shift across the autumn clock change counts the hour that really elapsed`() {
        val minutos = CalculadoraJornada.minutosTrabajados(
            entrada = madrid("2026-10-25", "00:00"),
            salida = madrid("2026-10-25", "08:00"),
            pausas = emptyList()
        )

        assertEquals(540, minutos, "the night the clocks go back has 25 hours, so 00:00->08:00 is 9h")
    }

    /** And the spring change in the other direction: 7 real hours, not 8. */
    @Test
    fun `a shift across the spring clock change counts the hour that was lost`() {
        val minutos = CalculadoraJornada.minutosTrabajados(
            entrada = madrid("2026-03-29", "00:00"),
            salida = madrid("2026-03-29", "08:00"),
            pausas = emptyList()
        )

        assertEquals(420, minutos, "the night the clocks go forward has 23 hours, so 00:00->08:00 is 7h")
    }

    /**
     * Breaks that exactly fill the shift give **zero** worked minutes, which is
     * a legitimate answer rather than an error.
     *
     * Worth pinning down, because it is the boundary that shows worked time can
     * never go negative while the overlap and in-range checks hold: breaks that
     * neither overlap nor fall outside the shift can sum at most its length. An
     * earlier version of this test asserted a rejection here and was simply
     * wrong about the requirement.
     */
    @Test
    fun `breaks exactly filling the shift give zero worked minutes, not an error`() {
        val dosPausasContiguas = CalculadoraJornada.minutosTrabajados(
            entrada = madrid("2026-10-05", "08:00"),
            salida = madrid("2026-10-05", "09:00"),
            pausas = listOf(
                IntervaloPausa(madrid("2026-10-05", "08:00"), madrid("2026-10-05", "08:40")),
                IntervaloPausa(madrid("2026-10-05", "08:40"), madrid("2026-10-05", "09:00"))
            )
        )
        assertEquals(0, dosPausasContiguas)

        val unaPausaCompleta = CalculadoraJornada.minutosTrabajados(
            entrada = madrid("2026-10-05", "08:00"),
            salida = madrid("2026-10-05", "09:00"),
            pausas = listOf(
                IntervaloPausa(madrid("2026-10-05", "08:00"), madrid("2026-10-05", "09:00"))
            )
        )
        assertEquals(0, unaPausaCompleta)
    }

    /** Contiguous breaks touch at a boundary; touching is not overlapping. */
    @Test
    fun `breaks that merely touch at a boundary are not treated as overlapping`() {
        val minutos = CalculadoraJornada.minutosTrabajados(
            entrada = madrid("2026-10-05", "07:00"),
            salida = madrid("2026-10-05", "16:00"),
            pausas = listOf(
                IntervaloPausa(madrid("2026-10-05", "09:00"), madrid("2026-10-05", "09:30")),
                IntervaloPausa(madrid("2026-10-05", "09:30"), madrid("2026-10-05", "10:00"))
            )
        )
        assertEquals(480, minutos)
    }

    @Test
    fun `an exit before the entry is rejected`() {
        assertFailsWith<ValoresIncoherentesException> {
            CalculadoraJornada.minutosTrabajados(
                entrada = madrid("2026-10-05", "16:00"),
                salida = madrid("2026-10-05", "07:00"),
                pausas = emptyList()
            )
        }
    }

    @Test
    fun `overlapping breaks are rejected`() {
        assertFailsWith<ValoresIncoherentesException> {
            CalculadoraJornada.minutosTrabajados(
                entrada = madrid("2026-10-05", "07:00"),
                salida = madrid("2026-10-05", "16:00"),
                pausas = listOf(
                    IntervaloPausa(madrid("2026-10-05", "09:00"), madrid("2026-10-05", "10:00")),
                    IntervaloPausa(madrid("2026-10-05", "09:30"), madrid("2026-10-05", "10:30"))
                )
            )
        }
    }

    @Test
    fun `a break outside the shift is rejected`() {
        assertFailsWith<ValoresIncoherentesException> {
            CalculadoraJornada.minutosTrabajados(
                entrada = madrid("2026-10-05", "07:00"),
                salida = madrid("2026-10-05", "16:00"),
                pausas = listOf(
                    IntervaloPausa(madrid("2026-10-05", "17:00"), madrid("2026-10-05", "17:30"))
                )
            )
        }
    }
}
