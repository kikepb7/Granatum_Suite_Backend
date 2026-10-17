package com.granatum.core

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * A permit-all filter chain, for this module's HTTP tests only - the same
 * arrangement, and for the same reason, as `auth`'s (see its
 * `SeguridadPermisivaTestConfig`).
 *
 * `events` has Spring Security on its classpath but no chain of its own:
 * the real one is `SecurityConfig`, in `app` (principle IV). Without this,
 * Boot's default security answers `401` to everything before a controller
 * runs. These tests therefore cover the contract - status codes, error
 * shapes, JSON - and **not** authorisation, which `app` covers with the real
 * chain. This is not evidence that the event routes are open.
 */
@TestConfiguration
class SeguridadPermisivaTestConfig {

    @Bean
    fun cadenaPermisiva(httpSecurity: HttpSecurity): SecurityFilterChain =
        httpSecurity
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .build()
}
