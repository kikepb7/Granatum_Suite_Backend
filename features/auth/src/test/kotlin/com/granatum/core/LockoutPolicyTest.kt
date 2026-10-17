package com.granatum.core

import com.granatum.core.domain.model.LockoutState
import com.granatum.core.domain.service.LockoutPolicy
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The escalating lockout (FR-013 to FR-016c).
 *
 * A pure unit test with a fixed instant, which is only possible because the
 * policy is a pure function. Expressed as a SQL `CASE` the four durations would
 * live where no unit test could reach them.
 */
class LockoutPolicyTest {

    private val now: Instant = Instant.parse("2026-10-05T09:00:00Z")

    private fun failTimes(times: Int, initial: LockoutState = LockoutState()): LockoutState {
        var state = initial
        repeat(times) { state = LockoutPolicy.afterFailure(state, now) }
        return state
    }

    @Test
    fun `four failures do not lock the account`() {
        val state = failTimes(4)

        assertEquals(4, state.failedAttempts)
        assertEquals(0, state.level)
        assertNull(state.lockedUntil)
        assertFalse(state.isLocked(now))
    }

    @Test
    fun `the fifth failure locks it for one minute`() {
        val state = failTimes(5)

        assertEquals(1, state.level)
        assertEquals(now.plus(Duration.ofMinutes(1)), state.lockedUntil)
        assertTrue(state.isLocked(now))
        assertEquals(
            0,
            state.failedAttempts,
            "the counter resets when the lockout is applied: FR-016c says the next " +
                "lockout needs another five failures once this one has expired, and " +
                "without the reset the first failure afterwards would re-lock instantly"
        )
    }

    /** FR-016a and SC-012: 1, 5, 15, 60. */
    @Test
    fun `the lockout escalates one five fifteen and sixty minutes`() {
        val expected = listOf(1L, 5L, 15L, 60L)
        var state = LockoutState()
        var instant = now

        expected.forEachIndexed { index, minutes ->
            state = failTimes(5, state.copy(lockedUntil = null))
            assertEquals(index + 1, state.level)
            assertEquals(
                instant.plus(Duration.ofMinutes(minutes)),
                state.lockedUntil,
                "lockout number ${index + 1} must last $minutes minutes"
            )
            instant = now
        }
    }

    @Test
    fun `the fifth and later lockouts stay at sixty minutes`() {
        var state = LockoutState(level = 4)
        state = failTimes(5, state)

        assertEquals(4, state.level, "the level caps at 4")
        assertEquals(now.plus(Duration.ofMinutes(60)), state.lockedUntil)
    }

    /**
     * **FR-016c, the rule the whole protection rests on.**
     *
     * A failure while a lockout is active must leave the state byte for byte as
     * it was. If it extended the deadline, anyone could keep a person locked out
     * indefinitely without ever knowing their password - a free denial of
     * service, and in this product being locked out means being unable to clock
     * in, which opens a gap in a record that carries legal weight.
     */
    @Test
    fun `a failure during an active lockout changes absolutely nothing`() {
        val lockedOut = failTimes(5)
        val originalDeadline = lockedOut.lockedUntil

        var state = lockedOut
        repeat(50) { state = LockoutPolicy.afterFailure(state, now.plusSeconds(10)) }

        assertEquals(
            lockedOut,
            state,
            "fifty failures during the lockout must not change the state at all - " +
                "otherwise the protection becomes a denial of service against the person"
        )
        assertEquals(originalDeadline, state.lockedUntil)
    }

    @Test
    fun `once the lockout expires failures count again`() {
        val lockedOut = failTimes(5)
        val after = lockedOut.lockedUntil!!.plusSeconds(1)

        val next = LockoutPolicy.afterFailure(lockedOut, after)

        assertEquals(1, next.failedAttempts)
        assertEquals(1, next.level, "the level survives the expiry: that is what makes the next one longer")
    }

    /** FR-015 and FR-016b. */
    @Test
    fun `a successful sign in clears the counter and the level`() {
        val next = LockoutPolicy.afterSuccess()

        assertEquals(0, next.failedAttempts)
        assertEquals(
            0,
            next.level,
            "resetting the level is the part that matters: somebody who mistyped once " +
                "should not still carry a one-hour penalty weeks later"
        )
        assertNull(next.lockedUntil)
    }

    @Test
    fun `the lockout lifts exactly at its deadline and not a moment later`() {
        val state = failTimes(5)
        val limit = state.lockedUntil!!

        assertTrue(state.isLocked(limit.minusMillis(1)))
        assertFalse(state.isLocked(limit), "at the deadline the account is usable again")
    }

    @Test
    fun `a state that was never locked is never blocked`() {
        assertFalse(LockoutState().isLocked(now))
    }
}
