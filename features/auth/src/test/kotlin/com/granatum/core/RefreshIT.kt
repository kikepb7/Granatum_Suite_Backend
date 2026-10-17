package com.granatum.core

import com.granatum.core.domain.exception.InactiveEmployeeAccountException
import com.granatum.core.domain.exception.InvalidRefreshTokenException
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.database.entities.RefreshSessionEntity
import com.granatum.core.infrastructure.database.repositories.SecurityEventRepository
import com.granatum.core.infrastructure.database.repositories.RefreshSessionRepository
import com.granatum.core.service.AuthenticationService
import com.granatum.core.service.RefreshTokenGenerator
import com.granatum.core.service.JwtService
import com.granatum.core.service.SessionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Renewing a session (US2): FR-007, FR-008 and FR-010, plus SC-010.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class RefreshIT : BaseAuthIT() {

    @Autowired lateinit var authentication: AuthenticationService
    @Autowired lateinit var sessionService: SessionService
    @Autowired lateinit var sessions: RefreshSessionRepository
    @Autowired lateinit var events: SecurityEventRepository
    @Autowired lateinit var tokenGenerator: RefreshTokenGenerator
    @Autowired lateinit var jwtService: JwtService

    private val password = "Granatum-2026!"

    @Test
    fun `renewing returns a brand new pair`() {
        val account = activeAccount(password = password, role = Role.MANAGER)
        val original = authentication.signIn(account.email, password)

        val renewed = sessionService.rotate(original.refreshToken)

        assertTrue(jwtService.validateAccessToken(renewed.accessToken))
        assertTrue(renewed.refreshToken != original.refreshToken, "the token rotates")
        assertEquals(
            Role.MANAGER,
            jwtService.getRoleFromToken(renewed.accessToken),
            "the role survives the rotation, or every renewal would silently demote somebody"
        )
        assertEquals(account.employeeId, jwtService.getSubjectFromToken(renewed.accessToken))
    }

    /** FR-008 and SC-004: one use, and one only. */
    @Test
    fun `the presented token stops working immediately`() {
        val account = activeAccount(password = password)
        val original = authentication.signIn(account.email, password)

        sessionService.rotate(original.refreshToken)

        assertFailsWith<InvalidRefreshTokenException> {
            sessionService.rotate(original.refreshToken)
        }
    }

    @Test
    fun `an unknown token is rejected`() {
        assertFailsWith<InvalidRefreshTokenException> {
            sessionService.rotate(tokenGenerator.generate())
        }
    }

    @Test
    fun `an expired token is rejected`() {
        val account = activeAccount(password = password)
        val token = tokenGenerator.generate()
        sessions.save(
            RefreshSessionEntity(
                account = account,
                tokenHash = tokenGenerator.hash(token),
                // Inserted already expired: the clock validator cannot be
                // waited out in a test, and what is under test is the WHERE
                // clause, not the passage of time.
                expiresAt = Instant.now().minus(1, ChronoUnit.DAYS),
                createdAt = Instant.now().minus(31, ChronoUnit.DAYS)
            )
        )

        assertFailsWith<InvalidRefreshTokenException> { sessionService.rotate(token) }
    }

    @Test
    fun `a revoked token is rejected`() {
        val account = activeAccount(password = password)
        val pair = authentication.signIn(account.email, password)

        sessionService.close(pair.refreshToken)

        assertFailsWith<InvalidRefreshTokenException> { sessionService.rotate(pair.refreshToken) }
    }

    /**
     * FR-010 and SC-010. Distinguishable from the generic rejection on purpose:
     * whoever presents a valid refresh token has already proved they hold the
     * account, so there is no existence to hide - and a clear answer stops them
     * retyping their password over and over.
     */
    @Test
    fun `renewing is refused when the person has been dismissed`() {
        val employeeId = UUID.randomUUID()
        val account = activeAccount(password = password, employeeId = employeeId)
        val pair = authentication.signIn(account.email, password)

        directory.registerInactive(employeeId)

        assertFailsWith<InactiveEmployeeAccountException> { sessionService.rotate(pair.refreshToken) }
    }

    /**
     * The status check runs **before** the token is claimed, so a dismissed
     * person gets the same clear answer every time instead of
     * `INVALID_REFRESH_TOKEN` from the second attempt onwards.
     */
    @Test
    fun `a refused renewal for dismissal does not burn the token`() {
        val employeeId = UUID.randomUUID()
        val account = activeAccount(password = password, employeeId = employeeId)
        val pair = authentication.signIn(account.email, password)
        directory.registerInactive(employeeId)

        assertFailsWith<InactiveEmployeeAccountException> { sessionService.rotate(pair.refreshToken) }
        assertFailsWith<InactiveEmployeeAccountException> { sessionService.rotate(pair.refreshToken) }

        assertNull(
            sessions.findByTokenHash(tokenGenerator.hash(pair.refreshToken))?.usedAt,
            "the token must still be unused: burning it would answer a different error " +
                "for the same situation on the second try"
        )
    }

    /**
     * Reusing an already-used token is its own event: it is the only signal of
     * possible theft the feature leaves, so collapsing it into the generic
     * rejection would erase the one thing worth investigating.
     */
    @Test
    fun `reusing a consumed token is recorded distinctly from an unknown one`() {
        val account = activeAccount(password = password)
        val pair = authentication.signIn(account.email, password)
        sessionService.rotate(pair.refreshToken)

        assertFailsWith<InvalidRefreshTokenException> { sessionService.rotate(pair.refreshToken) }

        val types = events.findAllByAccountIdOrderByOccurredAtDesc(account.id).map { it.type }
        assertTrue(
            types.contains(SecurityEventType.REFRESH_TOKEN_REUSED),
            "got $types"
        )
    }

    /**
     * D-007: the chain is deliberately **not** revoked on reuse. The usual
     * cause is a mobile client retrying a renewal whose response was lost, and
     * revoking everything would throw someone out of every device because of a
     * bad network - which here means being unable to clock in.
     */
    @Test
    fun `reusing a consumed token does not revoke the rest of the chain`() {
        val account = activeAccount(password = password)
        val first = authentication.signIn(account.email, password)
        val second = sessionService.rotate(first.refreshToken)

        assertFailsWith<InvalidRefreshTokenException> { sessionService.rotate(first.refreshToken) }

        val third = sessionService.rotate(second.refreshToken)
        assertNotNull(
            third.accessToken,
            "the live token must survive the reuse of a dead one, or a flaky network " +
                "would log the person out of everything"
        )
    }

    @Test
    fun `each renewal leaves its own session row`() {
        val account = activeAccount(password = password)
        val first = authentication.signIn(account.email, password)
        sessionService.rotate(first.refreshToken)

        assertEquals(
            1,
            sessions.countByAccountIdAndUsedAtIsNullAndRevokedAtIsNull(account.id),
            "exactly one live session after a rotation: the old one is consumed and the " +
                "new one replaces it"
        )
    }
}
