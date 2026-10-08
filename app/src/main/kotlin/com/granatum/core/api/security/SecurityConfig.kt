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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.web.cors.CorsConfigurationSource

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
        entryPointJson: EntryPointJson,
        limitadorPorOrigen: LimitadorPorOrigen,
        corsConfigurationSource: CorsConfigurationSource
    ): SecurityFilterChain {
        return httpSecurity
            .csrf { it.disable() }
            // Feature 006: browser origins from seguridad.cors.origenes, none by
            // default (ConfiguracionCors).
            .cors { it.configurationSource(corsConfigurationSource) }
            // Feature 006, FR-011: on top of Spring Security's defaults
            // (nosniff, X-Frame-Options DENY, Cache-Control no-store, HSTS on
            // secure requests). The API serves JSON, CSV and PDF: nothing in a
            // response should ever load, run or be framed.
            .headers { h ->
                h.contentSecurityPolicy {
                    it.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
                }
                h.referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
                h.permissionsPolicyHeader { it.policy("camera=(), microphone=(), geolocation=(), payment=()") }
                h.httpStrictTransportSecurity { it.includeSubDomains(true).maxAgeInSeconds(31_536_000) }
            }
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
                    // Sign-up (feature 005): public, like login - there is
                    // nobody to authenticate yet. It grants nothing by itself:
                    // the request waits for an ADMIN's approval, and the only
                    // path that creates an account straight away needs the
                    // deploy-time bootstrap code. Rate per origin is the
                    // hardening feature's; the volume is bounded by
                    // auth.registro.max-pendientes.
                    .requestMatchers(HttpMethod.POST, "/api/auth/registro")
                    .permitAll()
                    // Listing, approving and rejecting sign-ups: ADMIN only,
                    // like every other way of handing out access.
                    .requestMatchers("/api/auth/registros", "/api/auth/registros/**")
                    .hasRole("ADMIN")
                    // FR-018 and FR-024: only ADMIN creates or resets
                    // credentials. ENCARGADO runs inventory and approves
                    // corrections, but does not hand out access.
                    .requestMatchers("/api/auth/cuentas/**", "/api/auth/cuentas")
                    .hasRole("ADMIN")
                    // ASYNC (feature 003): a streamed download ends with an async
                    // dispatch that runs the chain again, and JwtAuthFilter, a
                    // OncePerRequestFilter, skips it - so it arrived anonymous,
                    // was denied after the file had been sent, and the
                    // connection was dropped. An async dispatch only resumes a
                    // request whose REQUEST dispatch was already authorised; no
                    // client can start one. `ExportacionHttpIT` guards it.
                    .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD, DispatcherType.ASYNC)
                    .permitAll()
                    // The export log and file verification (feature 003, D-014):
                    // ADMIN only. Deliberately not under /api/fichajes, whose GET
                    // rule admits all four roles - nesting it there would have
                    // made one forgotten matcher order expose who exported whose
                    // register to everyone with read access.
                    .requestMatchers("/api/exportaciones", "/api/exportaciones/**")
                    .hasRole("ADMIN")
                    // Invoicing (feature 004): ADMIN only, every route under
                    // one prefix and one rule, so no route added later can be
                    // left unprotected by a forgotten matcher. Invoices say
                    // whom the company buys from and sells to, and carry the
                    // DNI of self-employed suppliers (research.md D-017).
                    .requestMatchers("/api/facturacion", "/api/facturacion/**")
                    .hasRole("ADMIN")
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
            // Feature 006: per-origin quotas run before the token is even
            // parsed, so a refused request costs a map lookup and nothing else.
            .addFilterBefore(FiltroLimitePorOrigen(limitadorPorOrigen), JwtAuthFilter::class.java)
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
