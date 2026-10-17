package com.granatum.core.service

import com.granatum.core.domain.contract.NewStaffRecord
import com.granatum.core.domain.contract.StaffRecords
import com.granatum.core.domain.exception.InvalidBootstrapCodeException
import com.granatum.core.domain.exception.AccountAlreadyExistsException
import com.granatum.core.domain.exception.InvalidDocumentException
import com.granatum.core.domain.exception.EmailAlreadyRegisteredException
import com.granatum.core.domain.exception.WeakPasswordException
import com.granatum.core.domain.service.PasswordPolicy
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.crypto.BoundedVerifier
import com.granatum.core.infrastructure.database.entities.AccountEntity
import com.granatum.core.infrastructure.database.repositories.AccountRepository
import com.granatum.core.validation.NifValidator
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** What the owner sends to sign up. `toString` hides everything personal. */
data class SignUpData(
    val email: String,
    val password: String,
    val name: String,
    val identityDocument: String,
    val bootstrapCode: String
) {
    override fun toString(): String = "SignUpData(bootstrapCode=***)"
}

/** The owner's ADMIN account and staff record, created on the spot. */
data class CreatedAdmin(val accountId: EntityId, val employeeId: EntityId)

/**
 * Sign-up, which only the business owner can do (feature 009; feature 005 US1).
 *
 * The owner signs up once, with the deploy-time bootstrap code, while the
 * installation has no ADMIN; everyone else is onboarded by an ADMIN through
 * [AccountService.onboard] and receives a temporary password. There are
 * no pending requests any more: feature 009 removed 005's self sign-up with
 * approval.
 *
 * Not transactional at the method level: Argon2 takes ~110 ms and a
 * transaction held across it would pin a connection for the whole hash.
 */
@Service
class SignUpService(
    private val accounts: AccountRepository,
    private val verifier: BoundedVerifier,
    private val staffRecords: StaffRecords,
    private val events: SecurityEventRecorder,
    private val transactions: TransactionTemplate,
    private val entityManager: EntityManager,
    @param:Value("\${auth.registro.codigo-arranque:}") private val bootstrapCode: String,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * The first ADMIN (005 FR-009 to FR-013, research.md D-002).
     *
     * The code is checked before anything else, so a wrong one costs nothing -
     * not even the hash. "Is there an ADMIN yet?" and the creation run under a
     * transaction-scoped advisory lock: two bootstraps with different addresses
     * would otherwise both read "no" and both create one, and no unique
     * constraint stops two different rows.
     */
    fun signUp(input: SignUpData): CreatedAdmin {
        if (!isBootstrapCodeCorrect(input.bootstrapCode)) rejectBootstrap()

        val email = EmailNormalizer.normalize(input.email)
        val identityDocument = NifValidator.normalize(input.identityDocument)
        if (!NifValidator.isValidDniOrNie(identityDocument)) throw InvalidDocumentException()
        val unmet = PasswordPolicy.validate(input.password)
        if (unmet.isNotEmpty()) throw WeakPasswordException(unmet)

        val hash = verifier.encode(input.password)

        val created = transactions.execute {
            entityManager.createNativeQuery("SELECT CAST(pg_advisory_xact_lock(:key) AS text)")
                .setParameter("key", BOOTSTRAP_LOCK_KEY)
                .singleResult

            if (accounts.existsByRole(Role.ADMIN)) return@execute null
            if (accounts.existsByEmail(email)) throw EmailAlreadyRegisteredException()

            val employeeId = staffRecords.findByDocument(identityDocument) ?: staffRecords.create(
                NewStaffRecord(
                    name = input.name.trim(),
                    identityDocument = identityDocument,
                    // 005 research.md D-008: documented defaults the ADMIN
                    // corrects later with PUT /api/empleados/{id}.
                    position = FIRST_ADMIN_POSITION,
                    contractType = "FULL_TIME",
                    hireDate = LocalDate.now(clock.withZone(MADRID))
                )
            )
            if (accounts.existsByEmployeeId(employeeId)) throw AccountAlreadyExistsException()

            val account = accounts.save(
                AccountEntity(
                    employeeId = employeeId,
                    email = email,
                    role = Role.ADMIN,
                    passwordHash = hash,
                    // The owner chose it; nobody else ever saw it.
                    passwordChangeRequired = false
                )
            )
            CreatedAdmin(account.id, employeeId)
        } ?: rejectBootstrap()

        events.record(SecurityEventType.INITIAL_ADMIN_CREATED, created.accountId)
        return created
    }

    private fun isBootstrapCodeCorrect(received: String): Boolean =
        bootstrapCode.isNotBlank() &&
            MessageDigest.isEqual(received.toByteArray(Charsets.UTF_8), bootstrapCode.toByteArray(Charsets.UTF_8))

    /** One answer for the three causes (005 D-002): nothing tells them apart. */
    private fun rejectBootstrap(): Nothing {
        events.record(SecurityEventType.BOOTSTRAP_REJECTED)
        throw InvalidBootstrapCodeException()
    }

    companion object {
        const val FIRST_ADMIN_POSITION = "Dirección"

        /** Arbitrary, fixed: identifies "creating the first ADMIN" among advisory locks. */
        private const val BOOTSTRAP_LOCK_KEY = 5_005_001L

        private val MADRID: ZoneId = ZoneId.of("Europe/Madrid")
    }
}
