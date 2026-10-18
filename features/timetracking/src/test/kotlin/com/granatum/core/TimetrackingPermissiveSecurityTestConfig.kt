package com.granatum.core

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * A permit-all filter chain, for this module's HTTP tests only.
 *
 * Same reasoning as `AuthPermissiveSecurityTestConfig`: the real chain lives in
 * `SecurityConfig`, in `app` (principle IV), and without a chain of its own
 * Boot's default security answers `401` before any controller runs. With this,
 * the HTTP tests here cover the **contract** - validation, status codes, JSON -
 * and leave authorisation to `app`. It is not proof that the routes are open.
 */
@TestConfiguration
class TimetrackingPermissiveSecurityTestConfig {

    @Bean
    fun permissiveChain(httpSecurity: HttpSecurity): SecurityFilterChain =
        httpSecurity
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .build()
}
