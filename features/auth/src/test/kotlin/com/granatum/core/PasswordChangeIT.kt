package com.granatum.core

import com.granatum.core.domain.exception.InvalidCredentialsException
import com.granatum.core.domain.exception.WeakPasswordException
import com.granatum.core.domain.exception.InvalidRefreshTokenException
import com.granatum.core.domain.type.PasswordRequirement
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.database.repositories.SecurityEventRepository
import com.granatum.core.service.AuthenticationService
import com.granatum.core.service.AccountService
import com.granatum.core.service.JwtService
import com.granatum.core.service.SessionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Changing a password (US4): FR-021 to FR-023b, plus SC-014.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class PasswordChangeIT : BaseAuthIT() {

    @Autowired lateinit var accountService: AccountService
    @Autowired lateinit var authentication: AuthenticationService
    @Autowired lateinit var sessionService: SessionService
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var events: SecurityEventRepository

    private val newPassword = "Granatum-2027!"

    /** Grants access and returns the account plus its temporary password. */
    private fun withAccess(): Pair<UUID, String> {
        val employeeId = UUID.randomUUID().also { directory.registerActive(it) }
        val (_, temporaryPassword) = accountService.grantAccess(employeeId, uniqueEmail(), Role.EMPLOYEE)
        return employeeId to temporaryPassword
    }

    /** FR-021: the flag comes off and the person can operate normally. */
    @Test
    fun `changing the password clears the pending flag`() {
        val (employeeId, temporaryPassword) = withAccess()

        val pair = accountService.changeEmployeePassword(employeeId, temporaryPassword, newPassword)

        assertFalse(pair.passwordChangeRequired)
        assertFalse(
            jwtService.requiresPasswordChange(pair.accessToken),
            "the new token must not carry the claim, or the person stays locked to the " +
                "change-password screen for ever"
        )
        assertEquals(
            Role.EMPLOYEE,
            jwtService.getRoleFromToken(pair.accessToken),
            "and it must carry the real role again"
        )
    }

    @Test
    fun `the new password is the one that works afterwards`() {
        val (employeeId, temporaryPassword) = withAccess()
        val account = accounts.findByEmployeeId(employeeId)!!

        accountService.changeEmployeePassword(employeeId, temporaryPassword, newPassword)

        assertNotNull(authentication.signIn(account.email, newPassword).accessToken)
        assertFailsWith<InvalidCredentialsException> {
            authentication.signIn(account.email, temporaryPassword)
        }
    }

    /** FR-022. */
    @Test
    fun `a wrong current password is refused`() {
        val (employeeId, _) = withAccess()

        assertFailsWith<InvalidCredentialsException> {
            accountService.changeEmployeePassword(employeeId, "no-es-la-actual", newPassword)
        }
    }

    /** FR-023 and SC-014. */
    @Test
    fun `a weak password is refused with the list of broken requirements`() {
        val (employeeId, temporaryPassword) = withAccess()

        val error = assertFailsWith<WeakPasswordException> {
            accountService.changeEmployeePassword(employeeId, temporaryPassword, "abc")
        }

        assertContains(error.requirements, PasswordRequirement.MIN_LENGTH)
        assertContains(error.requirements, PasswordRequirement.MISSING_UPPERCASE)
        assertContains(error.requirements, PasswordRequirement.MISSING_DIGIT)
        assertContains(error.requirements, PasswordRequirement.MISSING_SYMBOL)
    }

    /** FR-023: the rejection must not reproduce the password anywhere. */
    @Test
    fun `the rejection never quotes the password`() {
        val (employeeId, temporaryPassword) = withAccess()
        val secret = "zzSECRETOzz"

        val error = assertFailsWith<WeakPasswordException> {
            accountService.changeEmployeePassword(employeeId, temporaryPassword, secret)
        }

        assertFalse(error.message.contains("SECRETO", ignoreCase = true))
        assertFalse(error.requirements.any { it.name.contains("SECRETO", ignoreCase = true) })
    }

    /**
     * The policy is checked **before** the current password, so a weak new one
     * does not cost a ~110 ms Argon2 verification. It cannot leak anything: the
     * verdict depends only on the new password, which the caller already knows.
     */
    @Test
    fun `a weak password is refused before the current one is even checked`() {
        val (employeeId, _) = withAccess()

        assertFailsWith<WeakPasswordException> {
            accountService.changeEmployeePassword(employeeId, "actual-incorrecta", "abc")
        }
    }

    /** FR-023b: the requirement that chose Argon2 over BCrypt, end to end. */
    @Test
    fun `a 64 character accented passphrase is accepted and works`() {
        val (employeeId, temporaryPassword) = withAccess()
        val account = accounts.findByEmployeeId(employeeId)!!
        val passphrase = "Á".repeat(30) + "á".repeat(32) + "1!"

        accountService.changeEmployeePassword(employeeId, temporaryPassword, passphrase)

        assertNotNull(
            authentication.signIn(account.email, passphrase).accessToken,
            "126 bytes of UTF-8: BCrypt throws above 72, which is why the algorithm is " +
                "Argon2id"
        )
    }

    /**
     * ➕ An addition of the plan, not a requirement. The spec only demands this
     * on reset (FR-025). Changing a password is often the reaction to a
     * suspicion of theft, and leaving the other devices signed in empties the
     * gesture. The cost was accepted openly: changing it on the phone closes the
     * browser session.
     */
    @Test
    fun `changing the password closes the other sessions but not the new one`() {
        val (employeeId, temporaryPassword) = withAccess()
        val account = accounts.findByEmployeeId(employeeId)!!
        val browser = authentication.signIn(account.email, temporaryPassword)

        val changed = accountService.changeEmployeePassword(employeeId, temporaryPassword, newPassword)

        assertFailsWith<InvalidRefreshTokenException> {
            sessionService.rotate(browser.refreshToken)
        }
        assertNotNull(
            sessionService.rotate(changed.refreshToken).accessToken,
            "the session handed back by the change itself must survive, or the person is " +
                "logged out of the very device they just used"
        )
    }

    @Test
    fun `the change is recorded`() {
        val (employeeId, temporaryPassword) = withAccess()
        val account = accounts.findByEmployeeId(employeeId)!!

        accountService.changeEmployeePassword(employeeId, temporaryPassword, newPassword)

        val types = events.findAllByAccountIdOrderByOccurredAtDesc(account.id).map { it.type }
        assertTrue(types.contains(SecurityEventType.PASSWORD_CHANGED), "got $types")
    }

    @Test
    fun `the new hash is salted and differs from the old one`() {
        val (employeeId, temporaryPassword) = withAccess()
        val before = accounts.findByEmployeeId(employeeId)!!.passwordHash

        accountService.changeEmployeePassword(employeeId, temporaryPassword, newPassword)

        val after = accounts.findByEmployeeId(employeeId)!!.passwordHash
        assertFalse(before == after)
        assertTrue(after.startsWith("{argon2}"))
    }
}
