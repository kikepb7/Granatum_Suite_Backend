package com.granatum.core

import com.granatum.core.domain.exception.InvalidCredentialsException
import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.database.repositories.SecurityEventRepository
import com.granatum.core.service.AuthenticationService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lockout (US3): FR-013 to FR-016, plus SC-005.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class LockoutIT : BaseAuthIT() {

    @Autowired lateinit var authentication: AuthenticationService
    @Autowired lateinit var events: SecurityEventRepository

    private val password = "Granatum-2026!"

    private fun failSignIn(email: String, times: Int) = repeat(times) {
        assertFailsWith<InvalidCredentialsException> {
            authentication.signIn(email, "incorrecta-$it")
        }
    }

    private fun reload(id: UUID) = accounts.findById(id).orElseThrow()

    @Test
    fun `four failures do not lock the account`() {
        val account = activeAccount(password = password)

        failSignIn(account.email, 4)

        assertNull(reload(account.id).lockoutState.lockedUntil)
        assertNotNull(
            authentication.signIn(account.email, password).accessToken,
            "an honest mistake four times over must not cost access"
        )
    }

    /** FR-013. */
    @Test
    fun `five consecutive failures lock the account`() {
        val account = activeAccount(password = password)

        failSignIn(account.email, 5)

        val reloaded = reload(account.id)
        assertNotNull(reloaded.lockoutState.lockedUntil)
        assertEquals(1, reloaded.lockoutState.lockoutLevel.toInt())
        assertEquals(
            0,
            reloaded.lockoutState.failedAttempts.toInt(),
            "the counter resets when the lockout is applied: the next lockout needs " +
                "another five failures once this one has expired (FR-016c)"
        )
    }

    /** FR-014 and SC-005: the sixth attempt fails **with the right password**. */
    @Test
    fun `a locked account refuses even the correct password`() {
        val account = activeAccount(password = password)
        failSignIn(account.email, 5)

        assertFailsWith<InvalidCredentialsException> {
            authentication.signIn(account.email, password)
        }
    }

    /**
     * ➕ The plan's own decision, not the spec's: the rejection for a lockout is
     * the same exception, so the handler maps it to the same code and body as a
     * wrong password. A specific "locked" response would reveal that the address
     * exists and re-open the oracle FR-003 closes.
     *
     * The cost is accepted openly: the person does not learn how long to wait.
     */
    @Test
    fun `a locked account answers exactly like a wrong password`() {
        val account = activeAccount(password = password)
        val unlockedAccount = activeAccount(password = password)
        failSignIn(account.email, 5)

        val byLockout = assertFailsWith<InvalidCredentialsException> {
            authentication.signIn(account.email, password)
        }
        val byPassword = assertFailsWith<InvalidCredentialsException> {
            authentication.signIn(unlockedAccount.email, "incorrecta")
        }

        assertEquals(byPassword::class, byLockout::class)
        assertEquals(byPassword.message, byLockout.message)
    }

    /** FR-016: the lockout lifts by itself, with no manual intervention. */
    @Test
    fun `once the deadline passes the correct password works again`() {
        val account = activeAccount(password = password)
        failSignIn(account.email, 5)

        // The deadline is moved into the past rather than waiting a minute:
        // what is under test is that nothing has to happen for the lockout to
        // lift, not the passage of time itself.
        val locked = reload(account.id)
        locked.lockoutState.lockedUntil = Instant.now().minusSeconds(1)
        accounts.save(locked)

        assertNotNull(authentication.signIn(account.email, password).accessToken)
    }

    /** FR-015. */
    @Test
    fun `four failures and a success reset the counter`() {
        val account = activeAccount(password = password)
        failSignIn(account.email, 4)

        authentication.signIn(account.email, password)

        assertEquals(0, reload(account.id).lockoutState.failedAttempts.toInt())
    }

    /**
     * The counter is consecutive, not cumulative. Without the reset, four
     * mistakes spread over a year would lock someone out on the fifth.
     */
    @Test
    fun `the counter is consecutive and not cumulative`() {
        val account = activeAccount(password = password)

        repeat(3) {
            failSignIn(account.email, 4)
            authentication.signIn(account.email, password)
        }

        assertNull(
            reload(account.id).lockoutState.lockedUntil,
            "twelve failures total, never five in a row: the account must be open"
        )
    }

    @Test
    fun `an attempt against a locked account is recorded with its own type`() {
        val account = activeAccount(password = password)
        failSignIn(account.email, 5)

        assertFailsWith<InvalidCredentialsException> {
            authentication.signIn(account.email, password)
        }

        val types = events.findAllByAccountIdOrderByOccurredAtDesc(account.id).map { it.type }
        assertTrue(types.contains(SecurityEventType.ACCOUNT_LOCKED), "got $types")
        assertTrue(types.contains(SecurityEventType.LOGIN_ACCOUNT_LOCKED), "got $types")
        assertEquals(
            1,
            types.count { it == SecurityEventType.ACCOUNT_LOCKED },
            "the account closes once; the attempts against it are a different event"
        )
    }

    @Test
    fun `locking one account does not affect another`() {
        val locked = activeAccount(password = password)
        val unlocked = activeAccount(password = password)

        failSignIn(locked.email, 5)

        assertNotNull(authentication.signIn(unlocked.email, password).accessToken)
    }
}
