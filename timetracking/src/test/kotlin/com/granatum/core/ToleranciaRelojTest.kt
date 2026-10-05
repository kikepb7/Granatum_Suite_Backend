package com.granatum.core

import com.granatum.core.domain.exception.DesviacionRelojException
import com.granatum.core.service.ValidadorReloj
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The clock tolerance, with a fixed clock so the boundaries are exact rather
 * than approximately right. With `Clock.systemUTC()` a test at 72 hours minus
 * one second would be flaky by construction.
 */
class ToleranciaRelojTest {

    private val ahora: Instant = Instant.parse("2026-10-05T12:00:00Z")
    private val validador = ValidadorReloj(
        toleranciaFuturo = Duration.ofMinutes(5),
        toleranciaPasado = Duration.ofHours(72),
        clock = Clock.fixed(ahora, ZoneOffset.UTC)
    )

    @Test
    fun `the present is accepted`() {
        validador.validar(ahora)
    }

    @Test
    fun `four minutes into the future is accepted`() {
        validador.validar(ahora.plus(Duration.ofMinutes(4)))
    }

    /** Exactly at the limit is inside it: the rule rejects "more than". */
    @Test
    fun `exactly five minutes into the future is accepted`() {
        validador.validar(ahora.plus(Duration.ofMinutes(5)))
    }

    @Test
    fun `six minutes into the future is rejected`() {
        assertFailsWith<DesviacionRelojException> {
            validador.validar(ahora.plus(Duration.ofMinutes(6)))
        }
    }

    @Test
    fun `forty-eight hours into the past is accepted`() {
        validador.validar(ahora.minus(Duration.ofHours(48)))
    }

    @Test
    fun `exactly seventy-two hours into the past is accepted`() {
        validador.validar(ahora.minus(Duration.ofHours(72)))
    }

    @Test
    fun `seventy-three hours into the past is rejected`() {
        assertFailsWith<DesviacionRelojException> {
            validador.validar(ahora.minus(Duration.ofHours(73)))
        }
    }

    @Test
    fun `five days into the past is rejected`() {
        assertFailsWith<DesviacionRelojException> {
            validador.validar(ahora.minus(Duration.ofDays(5)))
        }
    }

    /**
     * The asymmetry is the design, so it is asserted directly: a deviation that
     * is fine in the past is refused in the future.
     */
    @Test
    fun `the tolerance is asymmetric`() {
        val unaHora = Duration.ofHours(1)

        validador.validar(ahora.minus(unaHora))

        assertFailsWith<DesviacionRelojException> {
            validador.validar(ahora.plus(unaHora))
        }
    }

    @Test
    fun `the limits are configurable`() {
        val estricto = ValidadorReloj(
            toleranciaFuturo = Duration.ofSeconds(30),
            toleranciaPasado = Duration.ofMinutes(10),
            clock = Clock.fixed(ahora, ZoneOffset.UTC)
        )

        estricto.validar(ahora.minus(Duration.ofMinutes(9)))
        assertFailsWith<DesviacionRelojException> {
            estricto.validar(ahora.minus(Duration.ofMinutes(11)))
        }
    }
}
