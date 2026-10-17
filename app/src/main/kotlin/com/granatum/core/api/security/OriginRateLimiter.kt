package com.granatum.core.api.security

import java.time.Clock
import java.time.Duration

/** The quotas of feature 006 (research.md D-003). */
enum class Quota {
    /** `POST /api/auth/login`. */
    LOGIN,

    /** `POST /api/auth/refresh` and `POST /api/auth/logout`. */
    SESSION,

    /** `POST /api/auth/sign-up`, bootstrap included. */
    SIGN_UP,

    /** Everything under `/api`. */
    GENERAL
}

/**
 * Per-origin request quotas, in memory (feature 006, research.md D-001).
 *
 * One bounded LRU map per quota: when it is full, the least recently used
 * address is forgotten, so inventing addresses cannot grow the heap (FR-006).
 * A forgotten address starts again with a full bucket - acceptable, because the
 * limit slows a rate; it is not a record.
 *
 * ## Personal data
 *
 * The address is personal data. It only exists here, as a map key, for as long
 * as it is in the map; nothing in this class or its filter logs it, and nothing
 * stores it (FR-006, principle VI).
 *
 * ## One instance
 *
 * Counters are per process. With several instances the effective limit is the
 * configured one times the number of instances; shared counters would need
 * infrastructure the constitution keeps out until a spec demonstrates the need.
 */
class OriginRateLimiter(
    quotas: Map<Quota, Pair<Int, Duration>>,
    private val maxAddresses: Int,
    private val clock: Clock = Clock.systemUTC()
) {

    private class Entry(val capacity: Int, val period: Duration, val buckets: LinkedHashMap<String, TokenBucket>)

    private val entries: Map<Quota, Entry> = quotas.mapValues { (_, quota) ->
        val (capacity, period) = quota
        val maximum = maxAddresses
        Entry(
            capacity,
            period,
            // accessOrder = true: iteration order is least recently used first,
            // which is what removeEldestEntry evicts.
            object : LinkedHashMap<String, TokenBucket>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TokenBucket>?): Boolean =
                    size > maximum
            }
        )
    }

    /**
     * Takes one request from [address]'s bucket for [quota]. `null` if allowed;
     * otherwise the seconds to wait. A quota that is not configured does not
     * limit.
     */
    fun consume(quota: Quota, address: String): Long? {
        val entry = entries[quota] ?: return null
        val now = clock.instant()
        synchronized(entry.buckets) {
            val bucket = entry.buckets.getOrPut(address) { TokenBucket(entry.capacity, entry.period, now) }
            return bucket.consume(now)
        }
    }

    /** For tests: how many addresses the quota currently remembers. */
    fun rememberedAddresses(quota: Quota): Int =
        entries[quota]?.let { synchronized(it.buckets) { it.buckets.size } } ?: 0
}
