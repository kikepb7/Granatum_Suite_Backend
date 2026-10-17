package com.granatum.core.service

import com.granatum.core.domain.contract.EmployeeDirectory
import com.granatum.core.domain.exception.InactiveEmployeeAccountException
import com.granatum.core.domain.exception.InvalidRefreshTokenException
import com.granatum.core.domain.model.TokenPair
import com.granatum.core.domain.type.EmployeeStatus
import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.database.repositories.AccountRepository
import com.granatum.core.infrastructure.database.repositories.RefreshSessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Keeping and ending a session (US2): FR-007 to FR-012.
 */
@Service
class SessionService(
    private val sessions: RefreshSessionRepository,
    private val accounts: AccountRepository,
    private val tokenGenerator: RefreshTokenGenerator,
    private val directory: EmployeeDirectory,
    private val authenticationService: AuthenticationService,
    private val events: SecurityEventRecorder,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * Rotates a refresh token: the one presented dies, a new pair is issued.
     *
     * ## The order of the checks is deliberate
     *
     * The employee's status is checked **before** the token is claimed. Claiming
     * first would burn the token of a dismissed person, so their second attempt
     * would answer `INVALID_REFRESH_TOKEN` instead of `EMPLOYEE_INACTIVE` -
     * a different error for the same situation, which is confusing and tells
     * them nothing useful. Not burning it costs nothing: they cannot use it
     * either way.
     *
     * ## Why the conditional UPDATE is the guarantee
     *
     * `markUsedIfValid` carries the whole "still usable" condition in its
     * `WHERE`, so the **database** picks the winner: of two simultaneous
     * requests presenting the same token, one affects a row and the other
     * affects none. Reading the row, checking it here and writing afterwards
     * would leave a window in which both pass and two pairs are issued - which
     * is exactly the hole task T060 of feature 001 had to be rewritten to close.
     * SC-004 asks for 100%, and only this gives it.
     */
    @Transactional
    fun rotate(refreshToken: String): TokenPair {
        val hash = tokenGenerator.hash(refreshToken)
        val now = clock.instant()

        val session = sessions.findByTokenHash(hash)
        if (session == null) {
            events.record(SecurityEventType.REFRESH_REJECTED)
            throw InvalidRefreshTokenException()
        }

        // Captured before the modifying query clears the persistence context.
        val accountId = session.account.id
        val alreadyUsed = session.usedAt != null

        // FR-010. Distinguishable from the generic rejection here, unlike at
        // sign-in: whoever presents a valid refresh token has already proved
        // they hold the account, so there is no existence left to hide from
        // them - and knowing the rejection is a dismissal rather than an expired
        // session saves them retyping their password over and over.
        if (directory.status(session.account.employeeId) != EmployeeStatus.ACTIVE) {
            events.record(SecurityEventType.LOGIN_EMPLOYEE_INACTIVE, accountId)
            throw InactiveEmployeeAccountException()
        }

        if (sessions.markUsedIfValid(hash, now) != 1) {
            // Zero rows means used, revoked or expired. Reuse of an
            // already-used token is recorded as its own event because it means
            // something different: it is the only signal of possible theft this
            // feature leaves.
            //
            // It does **not** revoke the rest of the chain, although the OAuth
            // security BCP would. The far more frequent cause of reuse is not
            // theft but a mobile client retrying a renewal whose response was
            // lost; revoking the chain would throw somebody out of every device
            // because of a bad network, and here being thrown out means being
            // unable to clock in, which opens a gap in a record that carries
            // legal weight. The scenario that really calls for cutting
            // everything - a lost device - has its own route in FR-025.
            events.record(
                if (alreadyUsed) SecurityEventType.REFRESH_TOKEN_REUSED
                else SecurityEventType.REFRESH_REJECTED,
                accountId
            )
            throw InvalidRefreshTokenException()
        }

        // Re-read after the clear: the entity captured above is detached now.
        val account = accounts.findById(accountId).orElseThrow { InvalidRefreshTokenException() }

        events.record(SecurityEventType.REFRESH_SUCCEEDED, accountId)
        return authenticationService.issuePair(account)
    }

    /**
     * Ends one session (FR-009), and **only** that one: the person's other
     * devices keep working (FR-012, SC-006).
     *
     * Idempotent. An unknown or already-revoked token also succeeds, because
     * logging out twice is not a failure - and a `404` here would distinguish
     * tokens that exist from tokens that do not, which is information worth
     * nothing to a legitimate client and worth something to an attacker.
     *
     * The access token stays cryptographically valid until it expires; that
     * window is its lifetime, and the spec puts closing it out of scope.
     */
    @Transactional
    fun close(refreshToken: String) {
        val hash = tokenGenerator.hash(refreshToken)
        val session = sessions.findByTokenHash(hash)
        val accountId = session?.account?.id

        val revoked = sessions.revokeByTokenHash(hash, REASON_LOGOUT, clock.instant())

        // Recorded only when something was actually closed. Logging a
        // no-op logout would fill the security log with events that mean
        // nothing happened, which makes the real ones harder to find.
        if (revoked > 0) {
            events.record(SecurityEventType.LOGOUT, accountId)
        }
    }

    companion object {
        const val REASON_LOGOUT = "LOGOUT"
    }
}
