package com.granatum.core

import com.granatum.core.domain.exception.InvalidCredentialsException
import com.granatum.core.domain.exception.AccountNotFoundException
import com.granatum.core.domain.exception.InvalidRefreshTokenException
import com.granatum.core.domain.service.PasswordPolicy
import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.database.repositories.SecurityEventRepository
import com.granatum.core.infrastructure.database.repositories.RefreshSessionRepository
import com.granatum.core.service.AuthenticationService
import com.granatum.core.service.AccountService
import com.granatum.core.service.RefreshTokenGenerator
import com.granatum.core.service.SessionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Resetting a password (US5): FR-024 to FR-026, plus SC-007.
 *
 * "Only an ADMIN may reset" (US5 scenario 4) is authorisation, and lives in
 * `app`, the only module with the real filter chain: see `AutorizacionCuentasIT`.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class PasswordResetIT : BaseAuthIT() {

    @Autowired lateinit var accountService: AccountService
    @Autowired lateinit var authentication: AuthenticationService
    @Autowired lateinit var sessionService: SessionService
    @Autowired lateinit var sessions: RefreshSessionRepository
    @Autowired lateinit var events: SecurityEventRepository
    @Autowired lateinit var tokenGenerator: RefreshTokenGenerator

    private val password = "Granatum-2026!"

    /** FR-024. */
    @Test
    fun `a reset leaves a temporary password pending change`() {
        val account = activeAccount(password = password)

        val (resetAccount, temporaryPassword) = accountService.resetPassword(account.employeeId)

        assertTrue(resetAccount.passwordChangeRequired)
        assertEquals(emptyList(), PasswordPolicy.validate(temporaryPassword), "FR-023a holds on reset too")

        val pair = authentication.signIn(account.email, temporaryPassword)
        assertTrue(pair.passwordChangeRequired, "the next sign-in must demand the change")
    }

    @Test
    fun `the old password stops working`() {
        val account = activeAccount(password = password)

        accountService.resetPassword(account.employeeId)

        assertFailsWith<InvalidCredentialsException> {
            authentication.signIn(account.email, password)
        }
    }

    /**
     * FR-025 and SC-007: **100%** of open sessions die. Two are opened so the
     * test cannot pass by accident on a reset that closes only the newest one.
     */
    @Test
    fun `a reset closes every open session`() {
        val account = activeAccount(password = password)
        val mobile = authentication.signIn(account.email, password)
        val browser = authentication.signIn(account.email, password)

        accountService.resetPassword(account.employeeId)

        assertFailsWith<InvalidRefreshTokenException> { sessionService.rotate(mobile.refreshToken) }
        assertFailsWith<InvalidRefreshTokenException> { sessionService.rotate(browser.refreshToken) }
        assertEquals(0, sessions.countByAccountIdAndUsedAtIsNullAndRevokedAtIsNull(account.id))
    }

    @Test
    fun `revoked sessions record the reset as their reason`() {
        val account = activeAccount(password = password)
        val pair = authentication.signIn(account.email, password)

        accountService.resetPassword(account.employeeId)

        assertEquals(
            "RESET",
            sessions.findByTokenHash(tokenGenerator.hash(pair.refreshToken))!!.revocationReason,
            "the reason is what distinguishes 'an ADMIN cut this off' from 'they logged out' " +
                "when somebody later asks why a session ended"
        )
    }

    /**
     * FR-026. Without this, resetting the password of somebody who locked
     * themselves out would leave them locked out holding a password they cannot
     * use yet.
     */
    @Test
    fun `a reset lifts the lockout`() {
        val account = activeAccount(password = password)
        repeat(5) {
            assertFailsWith<InvalidCredentialsException> {
                authentication.signIn(account.email, "incorrecta-$it")
            }
        }
        assertNotNull(accounts.findById(account.id).orElseThrow().lockoutState.lockedUntil)

        val (_, temporaryPassword) = accountService.resetPassword(account.employeeId)

        val state = accounts.findById(account.id).orElseThrow().lockoutState
        assertNull(state.lockedUntil)
        assertEquals(0, state.lockoutLevel.toInt(), "the level goes too, not only the deadline")
        assertEquals(0, state.failedAttempts.toInt())
        assertNotNull(authentication.signIn(account.email, temporaryPassword).accessToken)
    }

    @Test
    fun `resetting somebody without an account is refused`() {
        assertFailsWith<AccountNotFoundException> {
            accountService.resetPassword(UUID.randomUUID())
        }
    }

    @Test
    fun `two resets give two different temporary passwords`() {
        val account = activeAccount(password = password)

        val (_, firstTemporary) = accountService.resetPassword(account.employeeId)
        val (_, secondTemporary) = accountService.resetPassword(account.employeeId)

        assertTrue(firstTemporary != secondTemporary)
        assertFailsWith<InvalidCredentialsException> {
            authentication.signIn(account.email, firstTemporary)
        }
    }

    @Test
    fun `the reset is recorded`() {
        val account = activeAccount(password = password)

        accountService.resetPassword(account.employeeId)

        val types = events.findAllByAccountIdOrderByOccurredAtDesc(account.id).map { it.type }
        assertTrue(types.contains(SecurityEventType.PASSWORD_RESET), "got $types")
    }
}
