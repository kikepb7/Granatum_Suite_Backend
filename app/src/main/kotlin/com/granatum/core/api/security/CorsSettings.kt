package com.granatum.core.api.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import java.time.Duration

/**
 * Browser origins admitted by the API (feature 006, FR-012, FR-013,
 * research.md D-008), from `security.cors.allowed-origins` (CORS_ALLOWED_ORIGINS).
 *
 * A configuration is **always** registered, even with an empty list: then every
 * CORS request is refused with 403, rather than falling through to whatever the
 * framework does with no configuration. Deny by default, like the rest of
 * `SecurityConfig`.
 *
 * No credentials: the access token travels in the Authorization header, never
 * in a cookie, so the browser has nothing to send on its own.
 */
@Configuration
class CorsSettings {

    @Bean
    fun corsConfigurationSource(
        @Value("\${security.cors.allowed-origins:}") allowedOrigins: String
    ): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            this.allowedOrigins = allowedOrigins.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE")
            allowedHeaders = listOf("Authorization", "Content-Type")
            // What clients need to read: the downloaded file's name (exports,
            // invoice originals, reports) and how long to wait on 429/503.
            exposedHeaders = listOf("Content-Disposition", "Retry-After")
            allowCredentials = false
            maxAge = Duration.ofHours(1).seconds
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", configuration) }
    }
}
