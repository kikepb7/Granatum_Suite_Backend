package com.granatum.core

import com.granatum.core.domain.exception.ClockSkewException
import com.granatum.core.service.ClockSkewValidator
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
class ClockToleranceTest {

    private val now: Instant = Instant.parse("2026-10-05T12:00:00Z")
    private val validator = ClockSkewValidator(
        futureTolerance = Duration.ofMinutes(5),
        pastTolerance = Duration.ofHours(72),
        clock = Clock.fixed(now, ZoneOffset.UTC)
    )

    @Test
    fun `the present is accepted`() {
        validator.validate(now)
    }

    @Test
    fun `four minutes into the future is accepted`() {
        validator.validate(now.plus(Duration.ofMinutes(4)))
    }

    /** Exactly at the limit is inside it: the rule rejects "more than". */
    @Test
    fun `exactly five minutes into the future is accepted`() {
        validator.validate(now.plus(Duration.ofMinutes(5)))
    }

    @Test
    fun `six minutes into the future is rejected`() {
        assertFailsWith<ClockSkewException> {
            validator.validate(now.plus(Duration.ofMinutes(6)))
        }
    }

    @Test
    fun `forty-eight hours into the past is accepted`() {
        validator.validate(now.minus(Duration.ofHours(48)))
    }

    @Test
    fun `exactly seventy-two hours into the past is accepted`() {
        validator.validate(now.minus(Duration.ofHours(72)))
    }

    @Test
    fun `seventy-three hours into the past is rejected`() {
        assertFailsWith<ClockSkewException> {
            validator.validate(now.minus(Duration.ofHours(73)))
        }
    }

    @Test
    fun `five days into the past is rejected`() {
        assertFailsWith<ClockSkewException> {
            validator.validate(now.minus(Duration.ofDays(5)))
        }
    }

    /**
     * The asymmetry is the design, so it is asserted directly: a deviation that
     * is fine in the past is refused in the future.
     */
    @Test
    fun `the tolerance is asymmetric`() {
        val oneHour = Duration.ofHours(1)

        validator.validate(now.minus(oneHour))

        assertFailsWith<ClockSkewException> {
            validator.validate(now.plus(oneHour))
        }
    }

    @Test
    fun `the limits are configurable`() {
        val strict = ClockSkewValidator(
            futureTolerance = Duration.ofSeconds(30),
            pastTolerance = Duration.ofMinutes(10),
            clock = Clock.fixed(now, ZoneOffset.UTC)
        )

        strict.validate(now.minus(Duration.ofMinutes(9)))
        assertFailsWith<ClockSkewException> {
            strict.validate(now.minus(Duration.ofMinutes(11)))
        }
    }
}
