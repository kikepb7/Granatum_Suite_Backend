package com.granatum.core.service

import com.granatum.core.domain.contract.EmployeeDirectory
import com.granatum.core.domain.exception.InvalidCredentialsException
import com.granatum.core.domain.model.TokenPair
import com.granatum.core.domain.type.EmployeeStatus
import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.crypto.DecoyHash
import com.granatum.core.infrastructure.crypto.BoundedVerifier
import com.granatum.core.infrastructure.database.entities.AccountEntity
import com.granatum.core.infrastructure.database.entities.RefreshSessionEntity
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.AccountRepository
import com.granatum.core.infrastructure.database.repositories.RefreshSessionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Signing in (US1): FR-001 to FR-005.
 *
 * ## Every failure looks the same
 *
 * Unknown email, wrong password and dismissed person all leave as
 * [InvalidCredentialsException], with the same code and the same body. That
 * is FR-003 and SC-002: anything else turns sign-in into an oracle that reveals
 * which addresses are registered.
 *
 * ## And takes the same time
 *
 * A password hash is verified on **every** path - against the real hash when the
 * account exists, against the decoy when it does not. Without the decoy the
 * "no such account" path would skip ~110 ms of Argon2 and the difference would
 * be trivial to measure from outside.
 *
 * The decoy is verified for the dismissed-person case too, even though the
 * account exists: the check happens after the password has been verified
 * anyway, so the timing already matches.
 *
 * ## Why `signIn` is not transactional
 *
 * On purpose, and it is the single most important structural decision here.
 * Argon2 takes ~110 ms, and a transaction open across it would hold a database
 * connection - and, once the lockout transition took a row lock, that lock too -
 * for the whole verification. Someone hammering one address would then make that
 * person's own sign-ins queue behind theirs, turning the hardening into the
 * amplifier of a denial of service.
 *
 * So the verification runs with no transaction held, and the lockout transitions
 * live in [AccountLockoutManager], each in its own short transaction. That is also
 * why they are in a separate bean: Spring's proxy does not intercept a call made
 * on `this`, so a `@Transactional` method invoked from inside this class would
 * run with no transaction at all - silently.
 *
 * ## The clock
 *
 * Injected with a default, following the pattern of `timetracking`'s services: a
 * test can hand it a fixed clock without a Spring bean having to exist for it.
 */
@Service
class AuthenticationService(
    private val accounts: AccountRepository,
    private val sessions: RefreshSessionRepository,
    private val verifier: BoundedVerifier,
    private val decoyHash: DecoyHash,
    private val tokenGenerator: RefreshTokenGenerator,
    private val jwtService: JwtService,
    private val directory: EmployeeDirectory,
    private val lockoutManager: AccountLockoutManager,
    private val events: SecurityEventRecorder,
    @param:Value("\${jwt.expiration-minutes}") private val accessMinutes: Long,
    @param:Value("\${auth.refresh-expiration-days}") private val refreshDays: Long,
    private val clock: Clock = Clock.systemUTC()
) {

    fun signIn(email: String, password: String): TokenPair {
        val account = accounts.findByEmail(EmailNormalizer.normalize(email))

        if (account == null) {
            // The decoy exists for exactly this branch. Removing it would make
            // an unknown address answer measurably faster than a wrong
            // password, which is the enumeration oracle FR-003 closes.
            verifier.matches(password, decoyHash.value)
            events.record(SecurityEventType.LOGIN_UNKNOWN_ACCOUNT)
            throw InvalidCredentialsException()
        }

        // FR-014: a locked account is refused **even with the correct
        // password**. Rejected before the real hash is verified, on an unlocked
        // read, so no row lock is held while Argon2 runs.
        //
        // The decoy is verified anyway, for two reasons. The timing has to match
        // the other rejections, and - this is the plan's own decision, not the
        // spec's - the response is byte-identical to a wrong password. A
        // specific "account locked" body would reveal that the address exists
        // and would re-open the enumeration oracle FR-003 closes by the side
        // door.
        //
        // The cost is real and worth naming: somebody who mistypes five times
        // does not see "try again in a minute" and has no idea how long to
        // wait. It is a usability loss at the most frustrating moment, accepted
        // in exchange for not leaking which addresses are registered.
        if (account.lockoutState.toModel().isLocked(clock.instant())) {
            verifier.matches(password, decoyHash.value)
            events.record(SecurityEventType.LOGIN_ACCOUNT_LOCKED, account.id)
            throw InvalidCredentialsException()
        }

        if (!verifier.matches(password, account.passwordHash)) {
            // The transition runs in its own short transaction and the events
            // are written here, outside it. Not a style choice: the event
            // recorder opens a REQUIRES_NEW transaction, so writing from inside
            // the locked one would make every thread hold two connections and
            // deadlock the pool under load - see AccountLockoutManager.
            //
            // Distinct event types on purpose: LOGIN_FAILED is an attempt,
            // ACCOUNT_LOCKED is the moment the account closes. An
            // investigation asks for the second and would otherwise have to
            // infer it by counting the first.
            val justLocked = lockoutManager.recordFailure(account.id)
            events.record(SecurityEventType.LOGIN_FAILED, account.id)
            if (justLocked) {
                events.record(SecurityEventType.ACCOUNT_LOCKED, account.id)
            }
            throw InvalidCredentialsException()
        }

        // FR-002: checked here and not against a column of our own, because
        // whether someone is employed is a fact owned by `timetracking`. The
        // answer arrives through the `EmployeeDirectory` contract, so this
        // module never depends on that one (principle I).
        //
        // `null` - the person does not exist at all - is treated exactly like
        // INACTIVE: it means an orphan account, and an orphan account must not
        // let anybody in while FR-029c's sweep is what surfaces it.
        if (directory.status(account.employeeId) != EmployeeStatus.ACTIVE) {
            events.record(SecurityEventType.LOGIN_EMPLOYEE_INACTIVE, account.id)
            throw InvalidCredentialsException()
        }

        // FR-015 and FR-016b: counter and level back to zero.
        lockoutManager.recordSuccess(account.id)
        events.record(SecurityEventType.LOGIN_SUCCEEDED, account.id)

        // `account` is now stale with respect to the lockout columns, which is
        // harmless: the only fields read below are the role, the employee id
        // and the pending-change flag, and none of them was touched.
        return issuePair(account)
    }

    /**
     * Mints the pair and opens a session row.
     *
     * The access token's subject is the **empleado id**, not the account id:
     * feature 001 decided the JWT subject is the person whose working time is
     * recorded, so a fichaje is attributed to them with no lookup that could
     * drift. A reset replaces the credential without changing who the fichajes
     * belong to.
     */
    fun issuePair(account: AccountEntity): TokenPair {
        val now = clock.instant()

        val accessToken = jwtService.generateAccessToken(
            subject = account.employeeId,
            role = account.role,
            requiresPasswordChange = account.passwordChangeRequired
        )

        val refreshToken = tokenGenerator.generate()
        sessions.save(
            RefreshSessionEntity(
                account = account,
                // Only the hash is stored; the value above is the only time it
                // exists in clear (FR-011).
                tokenHash = tokenGenerator.hash(refreshToken),
                expiresAt = now.plus(refreshDays, ChronoUnit.DAYS),
                createdAt = now
            )
        )

        return TokenPair(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresInSeconds = accessMinutes * 60,
            passwordChangeRequired = account.passwordChangeRequired
        )
    }
}
