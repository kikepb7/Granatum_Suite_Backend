package com.granatum.core.api.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.convert.DurationStyle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * The per-origin quotas (feature 006, research.md D-003), read from
 * `security.rate-limits.*`.
 *
 * Each quota is one value, `N/period` - `10/1m`, `5/1h` - so a deployment
 * changes it with a single variable. `off` disables that quota.
 */
@Configuration
class RateLimitSettings {

    @Bean
    fun originRateLimiter(
        @Value("\${security.rate-limits.login}") login: String,
        @Value("\${security.rate-limits.session}") session: String,
        @Value("\${security.rate-limits.sign-up}") signUp: String,
        @Value("\${security.rate-limits.general}") general: String,
        @Value("\${security.rate-limits.max-addresses}") maxAddresses: Int
    ): OriginRateLimiter = OriginRateLimiter(
        quotas = buildMap {
            parse("security.rate-limits.login", login)?.let { put(Quota.LOGIN, it) }
            parse("security.rate-limits.session", session)?.let { put(Quota.SESSION, it) }
            parse("security.rate-limits.sign-up", signUp)?.let { put(Quota.SIGN_UP, it) }
            parse("security.rate-limits.general", general)?.let { put(Quota.GENERAL, it) }
        },
        maxAddresses = maxAddresses
    )

    companion object {
        /**
         * Parses `N/period`. A malformed value stops the application from
         * starting, naming the property: a typo must not silently mean "no
         * limit" on the sign-in route.
         */
        fun parse(property: String, value: String): Pair<Int, Duration>? {
            if (value.trim().equals("off", ignoreCase = true)) return null
            val parts = value.split("/")
            val capacity = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val period = parts.getOrNull(1)?.trim()?.let { runCatching { DurationStyle.SIMPLE.parse(it) }.getOrNull() }
            check(parts.size == 2 && capacity != null && capacity > 0 && period != null && !period.isNegative && !period.isZero) {
                "$property debe tener la forma N/periodo (por ejemplo 10/1m o 5/1h) u 'off'"
            }
            return capacity to period
        }
    }
}
