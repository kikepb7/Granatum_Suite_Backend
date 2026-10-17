package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.time.Instant

/**
 * The three columns that govern the escalating lockout, kept as one piece.
 *
 * They are embedded rather than left as three loose fields because they only
 * mean anything together, and every transition writes all three at once. Loose,
 * they invite the one mistake that silently breaks FR-016a: setting
 * `lockedUntil` without touching `lockoutLevel`, after which every lockout
 * lasts one minute forever and no test of a single lockout would notice.
 *
 * The logic that computes the next value lives in
 * [com.granatum.core.domain.service.LockoutPolicy], a pure object with no
 * Spring and no database, because that is the business rule of this feature and
 * principle V requires it to be unit-testable.
 */
@Embeddable
class LockoutStateEmbeddable(
    /**
     * Consecutive failures since the last success or lockout. Reset to 0 when a
     * lockout is applied, so each new lockout needs another five failures
     * (FR-016c).
     */
    @Column(name = "failed_attempts", nullable = false)
    var failedAttempts: Short = 0,

    /**
     * Survives the expiry of the lockout - it is what makes the next one last
     * five minutes instead of one. 0 means never locked; capped at 4.
     */
    @Column(name = "lockout_level", nullable = false)
    var lockoutLevel: Short = 0,

    /** `null` = not locked. The lockout lifts by comparison with the clock. */
    @Column(name = "locked_until")
    var lockedUntil: Instant? = null
)
