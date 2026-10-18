package com.granatum.core.service

import com.granatum.core.domain.contract.NewStaffRecord
import com.granatum.core.domain.contract.EmployeeDirectory
import com.granatum.core.domain.contract.StaffRecords
import com.granatum.core.domain.exception.InvalidCredentialsException
import com.granatum.core.domain.exception.AccountNotFoundException
import com.granatum.core.domain.exception.AccountAlreadyExistsException
import com.granatum.core.domain.exception.InvalidDocumentException
import com.granatum.core.domain.exception.EmailAlreadyRegisteredException
import com.granatum.core.domain.exception.EmployeeNotInDirectoryException
import com.granatum.core.domain.exception.WeakPasswordException
import com.granatum.core.domain.model.TokenPair
import com.granatum.core.domain.service.PasswordPolicy
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.crypto.TemporaryPasswordGenerator
import com.granatum.core.infrastructure.crypto.BoundedVerifier
import com.granatum.core.infrastructure.database.entities.AccountEntity
import com.granatum.core.infrastructure.database.repositories.AccountRepository
import com.granatum.core.infrastructure.database.repositories.RefreshSessionRepository
import com.granatum.core.validation.NifValidator
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate

/**
 * Creating and changing credentials (US4): FR-018 to FR-023b, FR-027, FR-029b
 * to FR-029d.
 *
 * Not transactional at the method level, for the same reason as
 * [AuthenticationService]: Argon2 takes ~110 ms and a transaction held across it
 * would pin a connection for the whole hash. The individual writes are atomic
 * on their own.
 */
@Service
class AccountService(
    private val accounts: AccountRepository,
    private val sessions: RefreshSessionRepository,
    private val verifier: BoundedVerifier,
    private val passwordGenerator: TemporaryPasswordGenerator,
    private val directory: EmployeeDirectory,
    private val writer: CredentialWriter,
    private val authenticationService: AuthenticationService,
    private val events: SecurityEventRecorder,
    // Feature 009: onboarding creates the staff record too, through the
    // contract timetracking implements (principle I).
    private val staffRecords: StaffRecords,
    private val transactions: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC()
) {

    /** The staff record and the account an ADMIN asks for in one onboarding (feature 009). */
    data class OnboardingData(
        val name: String,
        val lastName: String,
        val identityDocument: String,
        val position: String,
        val contractType: String,
        val hireDate: LocalDate,
        val email: String,
        val role: Role
    ) {
        override fun toString(): String = "OnboardingData(contractType=$contractType, role=$role)"
    }

    /** What onboarding produced, and the clear temporary password - the only moment it exists. */
    data class OnboardingResult(val account: AccountEntity, val staffRecordCreated: Boolean, val temporaryPassword: String) {
        override fun toString(): String = "OnboardingResult(accountId=${account.id}, staffRecordCreated=$staffRecordCreated)"
    }

    /**
     * An ADMIN onboards a person in one operation: staff record and account,
     * with a generated temporary password that must be changed on first
     * sign-in (feature 009, FR-004 to FR-009).
     *
     * ## Order
     *
     * The temporary password is generated and hashed first, outside any
     * transaction - Argon2 takes ~110 ms and must not pin a connection. Then,
     * in one transaction: the address is free, the staff record with this
     * document is found or created, it has no account yet, and the account is
     * stored. Any refusal rolls the whole thing back: never a staff record
     * without its account because the email was taken (FR-009).
     *
     * A record that already exists is linked rather than duplicated (FR-007):
     * the ADMIN may have registered the person in the staff register earlier,
     * before they needed access.
     */
    fun onboard(input: OnboardingData): OnboardingResult {
        val identityDocument = NifValidator.normalize(input.identityDocument)
        if (!NifValidator.isValidDniOrNie(identityDocument)) throw InvalidDocumentException()
        val email = EmailNormalizer.normalize(input.email)

        val temporaryPassword = passwordGenerator.generate()
        val hash = verifier.encode(temporaryPassword)

        val (account, staffRecordCreated) = transactions.execute {
            if (accounts.existsByEmail(email)) throw EmailAlreadyRegisteredException()

            val existing = staffRecords.findByDocument(identityDocument)
            if (existing != null && accounts.existsByEmployeeId(existing)) throw AccountAlreadyExistsException()
            val employeeId = existing ?: staffRecords.create(
                NewStaffRecord(
                    input.name.trim(), input.lastName.trim(), identityDocument, input.position.trim(),
                    input.contractType, input.hireDate
                )
            )

            accounts.save(
                AccountEntity(
                    employeeId = employeeId,
                    email = email,
                    role = input.role,
                    passwordHash = hash,
                    // FR-006: the ADMIN knows this password; only the person's
                    // own, set on first sign-in, is theirs.
                    passwordChangeRequired = true
                )
            ) to (existing == null)
        }!!

        events.record(SecurityEventType.ACCOUNT_CREATED, account.id)
        return OnboardingResult(account, staffRecordCreated, temporaryPassword)
    }

    /**
     * Grants access to a person who is already on the staff register, in one
     * operation (FR-029b).
     *
     * The temporary password is **generated**, not chosen by the `ADMIN`.
     * FR-023a requires the policy to apply to "the temporary password an `ADMIN`
     * generates or accepts", and generating it closes the dangerous half of that
     * sentence: if the administrator picks it, nothing stops them using the same
     * one for the whole workforce, and the hash would be irrelevant because the
     * password would be guessable by being known.
     *
     * @return the created account **and the clear temporary password**, which is
     * the only moment it exists.
     */
    fun grantAccess(employeeId: EntityId, email: String, role: Role): Pair<AccountEntity, String> {
        // FR-029c. The database cannot enforce this: there is no foreign key
        // across modules, so the check goes through the EmployeeDirectory
        // contract and the sweep in FR-029c is what catches the rows that go
        // stale afterwards.
        if (directory.status(employeeId) == null) {
            throw EmployeeNotInDirectoryException(employeeId)
        }

        // Two distinct conflicts on purpose: the ADMIN's way out differs - reset
        // in one case, use another address in the other.
        if (accounts.existsByEmployeeId(employeeId)) throw AccountAlreadyExistsException()

        val normalized = EmailNormalizer.normalize(email)
        if (accounts.existsByEmail(normalized)) throw EmailAlreadyRegisteredException()

        val temporaryPassword = passwordGenerator.generate()
        val account = accounts.save(
            AccountEntity(
                employeeId = employeeId,
                email = normalized,
                role = role,
                passwordHash = verifier.encode(temporaryPassword),
                // FR-019. Also the column default: every account is born from a
                // temporary password and there is no route that skips the change.
                passwordChangeRequired = true
            )
        )

        events.record(SecurityEventType.ACCOUNT_CREATED, account.id)
        return account to temporaryPassword
    }

    /**
     * The person changes their own password (FR-021, FR-022, FR-023).
     *
     * ## Why the policy is checked first
     *
     * Rejecting a weak password does not require knowing whether the current one
     * is right, and checking it first saves a ~110 ms Argon2 verification on
     * every typo. It also cannot leak anything: the policy verdict depends only
     * on the new password, which the caller already knows.
     *
     * ## Why the other sessions are revoked
     *
     * **This is an addition of the plan, not a requirement.** The spec only
     * demands it on reset (FR-025). The reason: changing a password is often the
     * reaction to a suspicion of theft, and leaving the other devices signed in
     * empties the gesture. The cost is visible and was accepted deliberately -
     * changing it on the phone closes the browser session.
     */
    fun changeEmployeePassword(employeeId: EntityId, currentPassword: String, newPassword: String): TokenPair {
        // Looked up by empleado id because that is what the JWT subject carries
        // (decided in feature 001). The account id never travels in a request.
        val account = accounts.findByEmployeeId(employeeId) ?: throw AccountNotFoundException()
        val accountId = account.id

        val unmet = PasswordPolicy.validate(newPassword)
        if (unmet.isNotEmpty()) throw WeakPasswordException(unmet)

        // FR-022. The same exception as a failed sign-in, so it maps to the same
        // 401: this is a credential check, and there is nothing else to say
        // about it.
        if (!verifier.matches(currentPassword, account.passwordHash)) {
            throw InvalidCredentialsException()
        }

        // Hash first, outside any transaction; then a short locked write that
        // touches only the credential. See CredentialWriter for the lost
        // update this avoids.
        val updated = writer.write(
            accountId = accountId,
            hash = verifier.encode(newPassword),
            changeRequired = false,
            liftLockout = false
        )
        events.record(SecurityEventType.PASSWORD_CHANGED, accountId)

        // Revoked before the new pair is issued, so the session opened below
        // survives and the person is not logged out of the device they just
        // used.
        sessions.revokeAllForAccount(accountId, REASON_PASSWORD_CHANGE, clock.instant())

        return authenticationService.issuePair(updated)
    }

    /**
     * An `ADMIN` resets somebody's password (FR-024 to FR-026).
     *
     * The way back when everything else fails: there is no recovery by email,
     * so this is how a forgotten password is replaced. Same mechanism as
     * granting access - a generated temporary password, pending change - plus
     * two things only a reset does:
     *
     *  - **every** live session is revoked (FR-025, SC-007), which is also the
     *    route for a lost device, and the reason D-007 can afford not to revoke
     *    the chain on token reuse;
     *  - the lockout is lifted (FR-026), or resetting the password of somebody
     *    who locked themselves out would leave them locked out with a password
     *    they cannot use yet.
     *
     * @return the account and the clear temporary password - the only moment it
     * exists.
     */
    fun resetPassword(employeeId: EntityId): Pair<AccountEntity, String> {
        val account = accounts.findByEmployeeId(employeeId) ?: throw AccountNotFoundException()

        val temporaryPassword = passwordGenerator.generate()
        val updated = writer.write(
            accountId = account.id,
            hash = verifier.encode(temporaryPassword),
            changeRequired = true,
            liftLockout = true
        )

        sessions.revokeAllForAccount(account.id, REASON_RESET, clock.instant())
        events.record(SecurityEventType.PASSWORD_RESET, account.id)

        return updated to temporaryPassword
    }

    companion object {
        const val REASON_PASSWORD_CHANGE = "PASSWORD_CHANGE"
        const val REASON_RESET = "RESET"
    }
}
