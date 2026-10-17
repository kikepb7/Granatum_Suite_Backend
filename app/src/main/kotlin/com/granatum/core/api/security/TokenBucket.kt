package com.granatum.core.api.security

import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A token bucket (feature 006, research.md D-001): holds up to [capacity]
 * tokens and refills continuously at [capacity] per `period`. Each request
 * takes one.
 *
 * Continuous rather than a fixed window, because a fixed window admits twice
 * the quota around its boundary - N at the end of one window and N at the start
 * of the next.
 *
 * Pure and not thread-safe: [OriginRateLimiter] serialises access. The clock
 * is passed in, so it is tested without waiting.
 */
class TokenBucket(private val capacity: Int, period: Duration, now: Instant) {

    init {
        require(capacity > 0) { "La capacidad de un cupo tiene que ser positiva" }
        require(!period.isNegative && !period.isZero) { "El periodo de un cupo tiene que ser positivo" }
    }

    /** Tokens per nanosecond. */
    private val rate: Double = capacity.toDouble() / period.toNanos()

    private var tokens: Double = capacity.toDouble()
    private var lastRefill: Instant = now

    /**
     * Takes one token. `null` if there was one; otherwise the seconds until the
     * next one, rounded up and at least 1 - what goes in `Retry-After`.
     */
    fun consume(now: Instant): Long? {
        refill(now)
        if (tokens >= 1.0) {
            tokens -= 1.0
            return null
        }
        val nanosUntilOne = (1.0 - tokens) / rate
        return max(1L, ceil(nanosUntilOne / 1_000_000_000.0).toLong())
    }

    private fun refill(now: Instant) {
        // A clock that goes backwards (NTP adjustment) mints nothing.
        if (now.isAfter(lastRefill)) {
            val elapsed = Duration.between(lastRefill, now).toNanos()
            tokens = min(capacity.toDouble(), tokens + elapsed * rate)
            lastRefill = now
        }
    }
}
