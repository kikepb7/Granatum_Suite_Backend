package com.granatum.core

import com.granatum.core.domain.exception.InvalidRefreshTokenException
import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.database.repositories.SecurityEventRepository
import com.granatum.core.infrastructure.database.repositories.RefreshSessionRepository
import com.granatum.core.service.AuthenticationService
import com.granatum.core.service.RefreshTokenGenerator
import com.granatum.core.service.SessionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ending a session (US2): FR-009 and FR-012, plus SC-006.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class LogoutIT : BaseAuthIT() {

    @Autowired lateinit var authentication: AuthenticationService
    @Autowired lateinit var sessionService: SessionService
    @Autowired lateinit var sessions: RefreshSessionRepository
    @Autowired lateinit var events: SecurityEventRepository
    @Autowired lateinit var tokenGenerator: RefreshTokenGenerator

    private val password = "Granatum-2026!"

    /**
     * SC-006, and the assertion that matters is the second one. A logout that
     * closed everything would also pass "the closed session no longer works",
     * which is why both halves are checked.
     */
    @Test
    fun `closing one session leaves the others working`() {
        val account = activeAccount(password = password)
        val mobile = authentication.signIn(account.email, password)
        val browser = authentication.signIn(account.email, password)

        sessionService.close(mobile.refreshToken)

        assertFailsWith<InvalidRefreshTokenException> {
            sessionService.rotate(mobile.refreshToken)
        }
        assertNotNull(
            sessionService.rotate(browser.refreshToken).accessToken,
            "the other device must keep working: a lost phone is revoked by logging it " +
                "out, not by throwing the person off every screen they own"
        )
    }

    /**
     * Idempotent. Logging out twice is not a failure, and a 404 here would
     * distinguish tokens that exist from tokens that do not - information worth
     * nothing to a legitimate client and worth something to an attacker.
     */
    @Test
    fun `closing twice is not an error`() {
        val account = activeAccount(password = password)
        val pair = authentication.signIn(account.email, password)

        sessionService.close(pair.refreshToken)
        sessionService.close(pair.refreshToken)
    }

    @Test
    fun `closing an unknown token is not an error either`() {
        sessionService.close(tokenGenerator.generate())
    }

    @Test
    fun `the closed session is marked revoked with its reason`() {
        val account = activeAccount(password = password)
        val pair = authentication.signIn(account.email, password)

        sessionService.close(pair.refreshToken)

        val session = sessions.findByTokenHash(tokenGenerator.hash(pair.refreshToken))
        assertNotNull(session)
        assertNotNull(session.revokedAt)
        assertEquals(
            "LOGOUT",
            session.revocationReason,
            "the reason distinguishes a logout from a reset, which is the difference " +
                "that matters when someone asks why a session ended"
        )
        assertEquals(null, session.usedAt, "revoked is not the same as rotated")
    }

    @Test
    fun `an already used token can still be logged out without error`() {
        val account = activeAccount(password = password)
        val first = authentication.signIn(account.email, password)
        sessionService.rotate(first.refreshToken)

        // The consumed row is left alone: it cannot be presented again anyway,
        // and overwriting it would lose the record of how it ended.
        sessionService.close(first.refreshToken)

        val session = sessions.findByTokenHash(tokenGenerator.hash(first.refreshToken))!!
        assertNotNull(session.usedAt)
        assertEquals(null, session.revokedAt)
    }

    @Test
    fun `a real logout is recorded and a no-op one is not`() {
        val account = activeAccount(password = password)
        val pair = authentication.signIn(account.email, password)

        sessionService.close(pair.refreshToken)
        val afterFirst = events.findAllByAccountIdOrderByOccurredAtDesc(account.id)
            .count { it.type == SecurityEventType.LOGOUT }

        sessionService.close(pair.refreshToken)
        val afterSecond = events.findAllByAccountIdOrderByOccurredAtDesc(account.id)
            .count { it.type == SecurityEventType.LOGOUT }

        assertEquals(1, afterFirst)
        assertEquals(
            1,
            afterSecond,
            "a no-op logout must not be recorded: filling the security log with events " +
                "that mean nothing happened makes the real ones harder to find"
        )
    }

    @Test
    fun `closing a session does not touch other accounts`() {
        val mine = activeAccount(password = password)
        val theirs = activeAccount(password = password)
        val myPair = authentication.signIn(mine.email, password)
        val theirPair = authentication.signIn(theirs.email, password)

        sessionService.close(myPair.refreshToken)

        assertTrue(
            sessionService.rotate(theirPair.refreshToken).refreshToken.isNotBlank(),
            "the revocation must be scoped to one row, not one account and certainly " +
                "not the whole table"
        )
    }
}
