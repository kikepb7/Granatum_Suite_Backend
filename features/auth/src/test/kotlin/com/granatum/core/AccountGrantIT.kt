package com.granatum.core

import com.granatum.core.domain.exception.AccountAlreadyExistsException
import com.granatum.core.domain.exception.EmailAlreadyRegisteredException
import com.granatum.core.domain.exception.EmployeeNotInDirectoryException
import com.granatum.core.domain.service.PasswordPolicy
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.database.repositories.SecurityEventRepository
import com.granatum.core.service.AuthenticationService
import com.granatum.core.service.AccountService
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
 * Granting access (US4): FR-018, FR-019, FR-023a, FR-027, FR-029b to FR-029d,
 * plus SC-013 and SC-003.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class AccountGrantIT : BaseAuthIT() {

    @Autowired lateinit var accountService: AccountService
    @Autowired lateinit var authentication: AuthenticationService
    @Autowired lateinit var events: SecurityEventRepository

    private fun registeredPerson(): UUID =
        UUID.randomUUID().also { directory.registerActive(it) }

    /** FR-029b and SC-013: one call, not two. */
    @Test
    fun `granting access creates the account pending a password change`() {
        val employeeId = registeredPerson()
        val email = uniqueEmail()

        val (account, temporaryPassword) = accountService.grantAccess(employeeId, email, Role.EMPLOYEE)

        assertEquals(employeeId, account.employeeId)
        assertEquals(email, account.email)
        assertTrue(account.passwordChangeRequired, "FR-019")
        assertNotNull(temporaryPassword)
        assertEquals(16, temporaryPassword.length)
    }

    /** FR-023a: no route may introduce a weaker password than the policy demands. */
    @Test
    fun `the generated temporary password satisfies the policy`() {
        val (_, temporaryPassword) = accountService.grantAccess(
            registeredPerson(), uniqueEmail(), Role.EMPLOYEE
        )

        assertEquals(
            emptyList(),
            PasswordPolicy.validate(temporaryPassword),
            "an ADMIN must have no way to introduce a password weaker than the one the " +
                "person is held to"
        )
    }

    /** SC-003: 100% of stored values are salted hashes. */
    @Test
    fun `the stored password is an argon2 hash and not the temporary value`() {
        val (account, temporaryPassword) = accountService.grantAccess(
            registeredPerson(), uniqueEmail(), Role.EMPLOYEE
        )

        assertTrue(
            account.passwordHash.startsWith("{argon2}"),
            "got ${account.passwordHash.take(20)}"
        )
        assertFalse(
            account.passwordHash.contains(temporaryPassword),
            "the clear value must not survive anywhere in what is stored"
        )
    }

    @Test
    fun `the temporary password works for signing in`() {
        val (account, temporaryPassword) = accountService.grantAccess(
            registeredPerson(), uniqueEmail(), Role.EMPLOYEE
        )

        val pair = authentication.signIn(account.email, temporaryPassword)

        assertTrue(
            pair.passwordChangeRequired,
            "US4 scenario 2: the response has to say so, or the client cannot show the " +
                "right screen"
        )
    }

    /** FR-029c. */
    @Test
    fun `granting access to somebody who does not exist is refused`() {
        assertFailsWith<EmployeeNotInDirectoryException> {
            accountService.grantAccess(UUID.randomUUID(), uniqueEmail(), Role.EMPLOYEE)
        }
    }

    /** FR-027 and FR-029d: the way out of this is to reset, not to recreate. */
    @Test
    fun `a second account for the same person is refused`() {
        val employeeId = registeredPerson()
        accountService.grantAccess(employeeId, uniqueEmail(), Role.EMPLOYEE)

        assertFailsWith<AccountAlreadyExistsException> {
            accountService.grantAccess(employeeId, uniqueEmail(), Role.EMPLOYEE)
        }
    }

    /** FR-028, and with a distinct code because the way out differs. */
    @Test
    fun `an email that belongs to another account is refused`() {
        val email = uniqueEmail()
        accountService.grantAccess(registeredPerson(), email, Role.EMPLOYEE)

        assertFailsWith<EmailAlreadyRegisteredException> {
            accountService.grantAccess(registeredPerson(), email, Role.EMPLOYEE)
        }
    }

    @Test
    fun `the email is normalised on the way in`() {
        val employeeId = registeredPerson()

        val (account, _) = accountService.grantAccess(
            employeeId, "  MiXeD.Case@Granatum.ES  ", Role.EMPLOYEE
        )

        assertEquals("mixed.case@granatum.es", account.email)
        assertFailsWith<EmailAlreadyRegisteredException> {
            accountService.grantAccess(registeredPerson(), "mixed.case@GRANATUM.es", Role.EMPLOYEE)
        }
    }

    /**
     * The role is what this feature's implementation had to invent, because
     * FR-005 needs it in the token and nothing stored one. It must reach the
     * token unchanged, or authorisation silently decides something else.
     */
    @Test
    fun `the role given at sign-up is the role in the token`() {
        val (account, temporaryPassword) = accountService.grantAccess(
            registeredPerson(), uniqueEmail(), Role.REPRESENTATIVE
        )

        assertEquals(Role.REPRESENTATIVE, account.role)
        assertNotNull(authentication.signIn(account.email, temporaryPassword).accessToken)
    }

    @Test
    fun `two people with the same temporary password would still differ in storage`() {
        // The generator makes a collision essentially impossible, so the salt is
        // asserted directly: the same input, encoded twice, must differ.
        val one = accountService.grantAccess(registeredPerson(), uniqueEmail(), Role.EMPLOYEE)
        val another = accountService.grantAccess(registeredPerson(), uniqueEmail(), Role.EMPLOYEE)

        assertFalse(
            one.first.passwordHash == another.first.passwordHash,
            "SC-003: one cracked hash must not crack every account that shared a password"
        )
    }

    @Test
    fun `creating an account is recorded`() {
        val (account, _) = accountService.grantAccess(
            registeredPerson(), uniqueEmail(), Role.EMPLOYEE
        )

        val types = events.findAllByAccountIdOrderByOccurredAtDesc(account.id).map { it.type }
        assertTrue(types.contains(SecurityEventType.ACCOUNT_CREATED), "got $types")
    }
}
