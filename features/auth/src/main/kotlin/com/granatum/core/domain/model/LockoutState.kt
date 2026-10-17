package com.granatum.core.domain.model

import java.time.Instant

/**
 * How close an account is to being locked, and until when.
 *
 * Immutable: every transition returns a new value rather than mutating this
 * one, which is what lets [com.granatum.core.domain.service.LockoutPolicy] be
 * a pure function and therefore unit-testable without a database (principle V).
 */
data class LockoutState(
    val failedAttempts: Int = 0,
    val level: Int = 0,
    val lockedUntil: Instant? = null
) {
    /**
     * Strictly `after`, so the instant the lockout expires the account is
     * already usable. One second of extra lockout would be harmless; the reason
     * to be precise is that FR-016c's test compares an unchanged state exactly.
     */
    fun isLocked(now: Instant): Boolean =
        lockedUntil != null && lockedUntil.isAfter(now)
}
