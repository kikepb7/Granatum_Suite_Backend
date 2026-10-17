package com.granatum.core

import com.granatum.core.domain.exception.InvalidCredentialsException
import com.granatum.core.service.AuthenticationService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The escalation (SC-012) and FR-016c, both end to end against the database.
 *
 * `LockoutPolicyTest` already proves the state machine as a pure function.
 * This test proves the **wiring**: that the computed state is written to all
 * three columns, together, and read back. The policy could be perfect and the
 * persistence still wrong - writing `locked_until` without `lockout_level`
 * would make every lockout last one minute forever, and no unit test of the
 * policy would notice.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class EscalatingLockoutIT : BaseAuthIT() {

    @Autowired lateinit var authentication: AuthenticationService

    private val password = "Granatum-2026!"

    private fun failSignIn(email: String, times: Int) = repeat(times) {
        assertFailsWith<InvalidCredentialsException> {
            authentication.signIn(email, "incorrecta-$it")
        }
    }

    private fun reload(id: UUID) = accounts.findById(id).orElseThrow()

    /** Expires the current lockout without waiting it out. */
    private fun serveLockout(id: UUID) {
        val account = reload(id)
        account.lockoutState.lockedUntil = Instant.now().minusSeconds(1)
        accounts.save(account)
    }

    private fun lockoutMinutes(id: UUID): Long {
        val account = reload(id)
        val deadline = account.lockoutState.lockedUntil!!
        return Duration.between(Instant.now(), deadline).toMinutes() + 1
    }

    @Test
    fun `the lockout escalates one five fifteen and sixty minutes`() {
        val account = activeAccount(password = password)

        listOf(1L, 5L, 15L, 60L).forEachIndexed { index, expected ->
            failSignIn(account.email, 5)

            assertEquals(
                index + 1,
                reload(account.id).lockoutState.lockoutLevel.toInt(),
                "lockout number ${index + 1} must be at level ${index + 1}"
            )
            assertEquals(
                expected,
                lockoutMinutes(account.id),
                "lockout number ${index + 1} must last $expected minutes"
            )

            serveLockout(account.id)
        }
    }

    @Test
    fun `from the fifth lockout on it stays at sixty minutes`() {
        val account = activeAccount(password = password)

        repeat(6) {
            failSignIn(account.email, 5)
            serveLockout(account.id)
        }
        failSignIn(account.email, 5)

        assertEquals(4, reload(account.id).lockoutState.lockoutLevel.toInt(), "the level caps at 4")
        assertEquals(60L, lockoutMinutes(account.id))
    }

    /**
     * **FR-016c, the rule the whole protection rests on, end to end.**
     *
     * A failure while the lockout is active must leave all three columns
     * untouched. If it extended the deadline, anyone could keep a person locked
     * out indefinitely without ever knowing their password - a free denial of
     * service, and in this product being locked out means being unable to clock
     * in, which opens a gap in a record that carries legal weight.
     */
    @Test
    fun `fifty failures during an active lockout change nothing in the database`() {
        val account = activeAccount(password = password)
        failSignIn(account.email, 5)

        val before = reload(account.id).lockoutState
        val levelBefore = before.lockoutLevel
        val attemptsBefore = before.failedAttempts
        val deadlineBefore = before.lockedUntil!!

        failSignIn(account.email, 50)

        val after = reload(account.id).lockoutState
        assertEquals(levelBefore, after.lockoutLevel, "the level must not move")
        assertEquals(attemptsBefore, after.failedAttempts, "the counter must not move")
        assertTrue(
            abs(Duration.between(deadlineBefore, after.lockedUntil!!).toMillis()) < 1000,
            "the deadline must not move: if it did, anyone could keep somebody locked " +
                "out for ever without knowing their password, and here that means " +
                "stopping them clocking in. antes=$deadlineBefore despues=${after.lockedUntil}"
        )
    }

    /** FR-016b: a successful sign-in sends the next lockout back to one minute. */
    @Test
    fun `a successful sign in resets the escalation`() {
        val account = activeAccount(password = password)

        failSignIn(account.email, 5)
        serveLockout(account.id)
        failSignIn(account.email, 5)
        assertEquals(2, reload(account.id).lockoutState.lockoutLevel.toInt())

        serveLockout(account.id)
        authentication.signIn(account.email, password)
        assertEquals(0, reload(account.id).lockoutState.lockoutLevel.toInt())

        failSignIn(account.email, 5)
        assertEquals(
            1L,
            lockoutMinutes(account.id),
            "back to one minute: somebody who mistyped one morning should not still be " +
                "serving an hour weeks later"
        )
    }

    /**
     * The level survives the expiry of the lockout - that is the whole point of
     * keeping it in a column rather than deriving it. A reset on expiry would
     * make every lockout last one minute.
     */
    @Test
    fun `the level survives the expiry of the lockout`() {
        val account = activeAccount(password = password)
        failSignIn(account.email, 5)
        serveLockout(account.id)

        assertEquals(1, reload(account.id).lockoutState.lockoutLevel.toInt())

        failSignIn(account.email, 5)
        assertEquals(5L, lockoutMinutes(account.id), "the second lockout is five minutes")
    }
}
