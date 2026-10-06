package com.granatum.core.api.security

import com.granatum.core.api.config.JwtAuthFilter
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Stateless JWT-bearer security, and the single place where public and
 * protected routes are declared (constitution principle IV).
 *
 * The four roles map as principle IV fixes them. A session that still owes a
 * password change carries no role at all - `JwtAuthFilter` grants it
 * `PWD_CHANGE_ONLY` instead - so every `hasAnyRole(...)` rule below refuses it
 * on its own, and `change-password` is the one route that lets it through.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    fun filterChain(
        httpSecurity: HttpSecurity,
        jwtAuthFilter: JwtAuthFilter,
        entryPointJson: EntryPointJson
    ): SecurityFilterChain {
        return httpSecurity
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/", "/actuator/**", "/api/dev/**")
                    .permitAll()
                    // The credential travels in the body, not in the
                    // Authorization header: there is nothing to authenticate
                    // with yet. Rate limiting for this endpoint belongs to the
                    // hardening feature; what bounds it today is the semaphore
                    // in VerificadorAcotado, which caps the memory a flood of
                    // sign-ins can reserve.
                    .requestMatchers(HttpMethod.POST, "/api/auth/login")
                    .permitAll()
                    // The refresh token *is* the credential for both of these,
                    // and it travels in the body. `logout` in particular: the
                    // normal moment to use it is after the access token has
                    // already expired, so requiring one would make the
                    // operation impossible exactly when it is needed.
                    .requestMatchers(HttpMethod.POST, "/api/auth/refresh", "/api/auth/logout")
                    .permitAll()
                    // The only operation a session pending a password change may
                    // perform (FR-020). A **grant**, listing PWD_CHANGE_ONLY
                    // alongside the real roles - never a negation. The filter in
                    // `common` hands out PWD_CHANGE_ONLY *instead of* the role,
                    // so every rule below refuses such a token on its own and
                    // this is the one place that lets it through.
                    .requestMatchers(HttpMethod.POST, "/api/auth/change-password")
                    .hasAnyAuthority(
                        JwtAuthFilter.AUTORIDAD_SOLO_CAMBIO_PASSWORD,
                        "ROLE_ADMIN",
                        "ROLE_ENCARGADO",
                        "ROLE_EMPLEADO",
                        "ROLE_REPRESENTANTE"
                    )
                    // FR-018 and FR-024: only ADMIN creates or resets
                    // credentials. ENCARGADO runs inventory and approves
                    // corrections, but does not hand out access.
                    .requestMatchers("/api/auth/cuentas/**", "/api/auth/cuentas")
                    .hasRole("ADMIN")
                    .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD)
                    .permitAll()
                    .requestMatchers("/api/materiales/**", "/api/categorias/**")
                    .hasAnyRole("ADMIN", "ENCARGADO")
                    // Staff management is ADMIN only: ENCARGADO runs inventory
                    // and approves corrections, but does not manage people.
                    .requestMatchers("/api/empleados/**")
                    .hasRole("ADMIN")
                    // REPRESENTANTE is read-only over the register, so it may
                    // reach these paths with GET and nothing else. Resource
                    // ownership (an EMPLEADO only seeing their own) is checked
                    // in the service against the JWT subject, because a path
                    // matcher cannot express it.
                    .requestMatchers(HttpMethod.GET, "/api/fichajes/**", "/api/correcciones/**")
                    .hasAnyRole("ADMIN", "ENCARGADO", "EMPLEADO", "REPRESENTANTE")
                    .requestMatchers("/api/fichajes/**", "/api/correcciones/**")
                    .hasAnyRole("ADMIN", "ENCARGADO", "EMPLEADO")
                    // Hardened from `authenticated()` to requiring a real role.
                    //
                    // A token pending a password change **is** authenticated, so
                    // with the previous catch-all it would reach any future
                    // route that had no explicit role rule - which is the
                    // opposite of deny-by-default and exactly the kind of hole
                    // nobody notices, because adding a route is what opens it.
                    //
                    // `ComodinSecurityConfigIT` guards this line, and it needs
                    // to: reverting it to `authenticated()` breaks nothing else.
                    .anyRequest()
                    .hasAnyRole("ADMIN", "ENCARGADO", "EMPLEADO", "REPRESENTANTE")
            }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling { configure ->
                // Replaces HttpStatusEntryPoint, which answered a 401 with an
                // empty body - no `code` and no way to tell an expired token
                // from an invalid one, which FR-006 requires. See EntryPointJson.
                configure.authenticationEntryPoint(entryPointJson)
                configure.accessDeniedHandler(entryPointJson)
            }
            .build()
    }
}
