package com.granatum.core

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * A permit-all filter chain, for this module's HTTP tests only.
 *
 * ## Why it is needed
 *
 * `auth` has `spring-boot-starter-security` on its classpath but defines no
 * `SecurityFilterChain`: the real one lives in `SecurityConfig`, in `app`, which
 * is where constitution principle IV requires the route map to live. With no
 * chain of its own, Boot's default security auto-configuration locks the whole
 * context down with HTTP Basic, and every request to this module's test server
 * answers `401` with an empty body before reaching a controller.
 *
 * ## What this means for what the tests prove
 *
 * This configuration deliberately makes the module's HTTP tests cover the
 * **contract** - status codes, error shapes, JSON serialisation - and **not**
 * authorisation. Authorisation is covered in `app`, the only module that has
 * both the real chain and the other features, which is the split D-016 calls
 * for.
 *
 * Stating that plainly matters: a permit-all chain in a test is exactly the kind
 * of thing that later gets mistaken for proof that the endpoints are open to
 * everyone, or worse, copied into production. It is neither - `app` is where
 * `/api/auth/login` is declared public and everything else is not.
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
