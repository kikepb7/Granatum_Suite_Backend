package com.granatum.core

import com.granatum.core.domain.exception.InvalidCredentialsException
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.database.repositories.SecurityEventRepository
import com.granatum.core.infrastructure.database.repositories.RefreshSessionRepository
import com.granatum.core.service.AuthenticationService
import com.granatum.core.service.RefreshTokenGenerator
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Signing in (US1): FR-001 to FR-005, plus SC-010.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class LoginIT : BaseAuthIT() {

    @Autowired lateinit var authentication: AuthenticationService
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var tokenGenerator: RefreshTokenGenerator
    @Autowired lateinit var events: SecurityEventRepository
    @Autowired lateinit var sessions: RefreshSessionRepository

    private val password = "Granatum-2026!"

    @Test
    fun `valid credentials return a token pair`() {
        val account = activeAccount(password = password)

        val pair = authentication.signIn(account.email, password)

        assertTrue(jwtService.validateAccessToken(pair.accessToken))
        assertNotNull(pair.refreshToken)
        assertEquals(15 * 60, pair.expiresInSeconds, "expiresIn travels in seconds")
        assertFalse(pair.passwordChangeRequired)
    }

    /** FR-005: the existing features consume this token unchanged. */
    @Test
    fun `the access token carries the person and their role`() {
        val employeeId = UUID.randomUUID()
        val account = activeAccount(password = password, role = Role.MANAGER, employeeId = employeeId)

        val pair = authentication.signIn(account.email, password)

        assertEquals(
            employeeId,
            jwtService.getSubjectFromToken(pair.accessToken),
            "the subject is the empleado id, not the account id: feature 001 decided " +
                "that, and it is what attributes a fichaje with no lookup that could drift"
        )
        assertEquals(Role.MANAGER, jwtService.getRoleFromToken(pair.accessToken))
    }

    @Test
    fun `a wrong password is rejected`() {
        val account = activeAccount(password = password)

        assertFailsWith<InvalidCredentialsException> {
            authentication.signIn(account.email, "Otra-Clave-2026!")
        }
    }

    /**
     * FR-003: the same exception type as a wrong password, so the handler maps
     * both to the same code and the same body. Anything else turns sign-in into
     * an oracle for which addresses are registered.
     */
    @Test
    fun `an unknown email is rejected exactly like a wrong password`() {
        val account = activeAccount(password = password)

        val byPassword = assertFailsWith<InvalidCredentialsException> {
            authentication.signIn(account.email, "incorrecta")
        }
        val byEmail = assertFailsWith<InvalidCredentialsException> {
            authentication.signIn(uniqueEmail(), "incorrecta")
        }

        assertEquals(byPassword.message, byEmail.message)
        assertEquals(byPassword::class, byEmail::class)
    }

    /** FR-002 and SC-010, with correct credentials. */
    @Test
    fun `a dismissed person cannot sign in even with the right password`() {
        val employeeId = UUID.randomUUID()
        val account = activeAccount(password = password, employeeId = employeeId)
        directory.registerInactive(employeeId)

        assertFailsWith<InvalidCredentialsException> {
            authentication.signIn(account.email, password)
        }
    }

    /**
     * An orphan account - one whose person no longer exists - must not let
     * anybody in either. The database cannot prevent the row (no foreign key
     * across modules), so the service has to refuse it; FR-029c's sweep is what
     * surfaces it for cleaning.
     */
    @Test
    fun `an account whose person does not exist cannot sign in`() {
        val employeeId = UUID.randomUUID()
        val account = activeAccount(password = password, employeeId = employeeId)
        directory.forget(employeeId)

        assertFailsWith<InvalidCredentialsException> {
            authentication.signIn(account.email, password)
        }
    }

    /** FR-028: the address is normalised in one place, so case must not matter. */
    @Test
    fun `the email is case and whitespace insensitive`() {
        val account = activeAccount(password = password, email = "ana.prueba@granatum.es")

        val pair = authentication.signIn("  ANA.PRUEBA@Granatum.ES  ", password)

        assertTrue(jwtService.validateAccessToken(pair.accessToken))
        assertEquals(account.email, "ana.prueba@granatum.es")
    }

    /**
     * FR-011: what is stored must not be usable. The session row holds the
     * SHA-256, and the clear value appears only in the response.
     */
    @Test
    fun `the refresh token is stored only as a hash`() {
        val account = activeAccount(password = password)

        val pair = authentication.signIn(account.email, password)

        val byValue = sessions.findByTokenHash(pair.refreshToken)
        assertEquals(null, byValue, "the clear value must not be the stored key")

        val byHash = sessions.findByTokenHash(tokenGenerator.hash(pair.refreshToken))
        assertNotNull(byHash, "the session is found by the hash of the token and only by it")
        assertEquals(64, tokenGenerator.hash(pair.refreshToken).length)
    }

    @Test
    fun `two sign ins open two independent sessions`() {
        val account = activeAccount(password = password)

        val mobile = authentication.signIn(account.email, password)
        val web = authentication.signIn(account.email, password)

        assertFalse(mobile.refreshToken == web.refreshToken, "FR-012: one session per device")
    }

    /** FR-017, and the shape of it: counts and account ids, never values. */
    @Test
    fun `each outcome leaves its own security event`() {
        val account = activeAccount(password = password)

        authentication.signIn(account.email, password)
        runCatching { authentication.signIn(account.email, "mal") }
        runCatching { authentication.signIn(uniqueEmail(), "mal") }

        val types = events.findAllByAccountIdOrderByOccurredAtDesc(account.id).map { it.type }
        assertTrue(types.contains(SecurityEventType.LOGIN_SUCCEEDED))
        assertTrue(types.contains(SecurityEventType.LOGIN_FAILED))

        // The unknown-email attempt is recorded with a null account id, never
        // with the address: it would be a personal datum about someone who is
        // not a user, over an attempt that may not have been theirs.
        assertTrue(
            events.count() >= 3,
            "the unknown-email attempt must be recorded too, with no account attached"
        )
    }

}
