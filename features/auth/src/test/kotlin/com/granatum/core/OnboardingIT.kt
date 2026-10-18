package com.granatum.core

import com.granatum.core.domain.exception.InvalidCredentialsException
import com.granatum.core.domain.exception.AccountAlreadyExistsException
import com.granatum.core.domain.exception.InvalidDocumentException
import com.granatum.core.domain.exception.EmailAlreadyRegisteredException
import com.granatum.core.domain.service.PasswordPolicy
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.database.repositories.SecurityEventRepository
import com.granatum.core.service.AuthenticationService
import com.granatum.core.service.AccountService
import com.granatum.core.service.AccountService.OnboardingData
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An ADMIN onboards a person in one step (feature 009, US2): FR-004 to FR-009. */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class OnboardingIT : BaseAuthIT() {

    @Autowired lateinit var service: AccountService
    @Autowired lateinit var authentication: AuthenticationService
    @Autowired lateinit var events: SecurityEventRepository

    private fun validDni(): String {
        val number = (10_000_000..99_999_999).random()
        return "$number${"TRWAGMYFPDXBNJZSQVHLCKE"[number % 23]}"
    }

    private fun onboarding(email: String = uniqueEmail(), identityDocument: String = validDni(), role: Role = Role.EMPLOYEE) =
        OnboardingData("Ana", "Florista Gómez", identityDocument, "Florista", "PART_TIME", LocalDate.of(2026, 10, 1), email, role)

    @Test
    fun `a new person gets a staff record and an account with a temporary password to change`() {
        val email = uniqueEmail()

        val onboarded = service.onboard(onboarding(email, role = Role.MANAGER))

        assertTrue(onboarded.staffRecordCreated)
        val staffRecord = directory.createdRecords.last()
        assertEquals("Ana", staffRecord.name)
        assertEquals("Florista Gómez", staffRecord.lastName, "feature 012: the last name reaches the staff record")
        assertEquals("Florista", staffRecord.position)
        assertEquals("PART_TIME", staffRecord.contractType)
        assertEquals(onboarded.account.employeeId, directory.findByDocument(staffRecord.identityDocument))

        val account = accounts.findByEmail(email)!!
        assertEquals(Role.MANAGER, account.role)
        assertTrue(account.passwordChangeRequired, "FR-006: the ADMIN knows it, so it must be changed")
        assertEquals(emptyList(), PasswordPolicy.validate(onboarded.temporaryPassword), "FR-005")
        assertFalse(account.passwordHash.contains(onboarded.temporaryPassword), "stored as a hash, never in clear")
        assertTrue(
            events.findAllByAccountIdOrderByOccurredAtDesc(account.id).any { it.type == SecurityEventType.ACCOUNT_CREATED }
        )
    }

    @Test
    fun `the temporary password signs in, and only for a password change`() {
        val email = uniqueEmail()
        val onboarded = service.onboard(onboarding(email))

        val pair = authentication.signIn(email, onboarded.temporaryPassword)

        assertTrue(pair.passwordChangeRequired)
    }

    /** FR-007: the person may already be on the staff register, without access. */
    @Test
    fun `an existing staff record with that document is linked, not duplicated`() {
        val identityDocument = validDni()
        val existing = directory.registerStaffRecord(identityDocument)
        val recordsBefore = directory.createdRecords.size

        val onboarded = service.onboard(onboarding(identityDocument = identityDocument))

        assertFalse(onboarded.staffRecordCreated)
        assertEquals(existing, onboarded.account.employeeId)
        assertEquals(recordsBefore, directory.createdRecords.size)
    }

    @Test
    fun `a staff record that already has an account is refused, and nothing is created`() {
        val identityDocument = validDni()
        activeAccount(employeeId = directory.registerStaffRecord(identityDocument))
        val email = uniqueEmail()

        assertFailsWith<AccountAlreadyExistsException> { service.onboard(onboarding(email, identityDocument)) }
        assertNull(accounts.findByEmail(email))
    }

    /** FR-008, FR-009: refused before the staff record is created, so none is left behind. */
    @Test
    fun `an address already in use is refused without creating the staff record`() {
        val used = activeAccount().email
        val identityDocument = validDni()
        val recordsBefore = directory.createdRecords.size

        assertFailsWith<EmailAlreadyRegisteredException> { service.onboard(onboarding(used, identityDocument)) }
        assertEquals(recordsBefore, directory.createdRecords.size)
        assertNull(directory.findByDocument(identityDocument))
    }

    @Test
    fun `a DNI with the wrong control letter is refused`() {
        val identityDocument = validDni()
        val wrongLetter = if (identityDocument.last() == 'T') 'R' else 'T'

        assertFailsWith<InvalidDocumentException> { service.onboard(onboarding(identityDocument = identityDocument.dropLast(1) + wrongLetter)) }
    }

    @Test
    fun `the temporary password does not work once changed`() {
        val email = uniqueEmail()
        val onboarded = service.onboard(onboarding(email))
        val employeeId = onboarded.account.employeeId

        service.changeEmployeePassword(employeeId, onboarded.temporaryPassword, "Granatum-Propia-2026!")

        assertFailsWith<InvalidCredentialsException> { authentication.signIn(email, onboarded.temporaryPassword) }
        assertFalse(authentication.signIn(email, "Granatum-Propia-2026!").passwordChangeRequired)
    }
}
