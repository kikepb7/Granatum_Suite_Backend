package com.granatum.core.api.config

import com.granatum.core.domain.type.EstadoToken
import com.granatum.core.service.JwtService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Reads the `Authorization: Bearer <token>` header, validates it and, when
 * valid, stores the token subject as the authenticated principal (with its
 * role as a `ROLE_*` authority) so it can be read anywhere via
 * [com.granatum.core.api.util.requestUserId] / [com.granatum.core.api.util.requestUserRole].
 *
 * ## Accounts that still owe a password change
 *
 * When the token carries the `pwd_change` claim, this filter grants **only**
 * [AUTORIDAD_SOLO_CAMBIO_PASSWORD] and deliberately **not** `ROLE_<role>`.
 *
 * That is what makes FR-020 hold without any other module knowing that `auth`
 * exists. Every authorisation rule in `SecurityConfig` is written as
 * `hasAnyRole(...)`, so they all start refusing such a token on their own, with
 * no change to `inventory` or `timetracking` - and the new rule for the
 * change-password route is a **grant**, never a negation.
 *
 * The difference matters. Principle VI already taught this project why with
 * `@Profile("!prod")`: a negation keeps passing its own test while quietly
 * failing to cover every case nobody thought of yet. A grant can only ever be
 * too narrow, which fails loudly.
 *
 * Note that `anyRequest()` in `SecurityConfig` must therefore require a real
 * role rather than merely `authenticated()`: a pending-change token *is*
 * authenticated, so a catch-all of `authenticated()` would leave every future
 * route without an explicit role rule within its reach.
 *
 * ## Expired versus invalid
 *
 * A rejection caused by expiry is marked on the request
 * ([ATRIBUTO_TOKEN_CADUCADO]) so the authentication entry point can answer
 * `TOKEN_ACCESO_EXPIRADO` instead of a generic rejection. FR-006 needs that
 * distinction: the application has to know whether to renew or to ask for the
 * password, and without it an expired token and a forged one look identical from
 * the outside.
 */
@Component
class JwtAuthFilter(
    private val jwtService: JwtService
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val authHeader = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            if (jwtService.validateAccessToken(token = authHeader)) {
                val subject = jwtService.getSubjectFromToken(token = authHeader)
                val role = jwtService.getRoleFromToken(token = authHeader)

                val authorities = if (jwtService.requiereCambioPassword(token = authHeader)) {
                    listOf(SimpleGrantedAuthority(AUTORIDAD_SOLO_CAMBIO_PASSWORD))
                } else {
                    listOf(SimpleGrantedAuthority("ROLE_${role.name}"))
                }

                val auth = UsernamePasswordAuthenticationToken(subject, null, authorities)
                SecurityContextHolder.getContext().authentication = auth
            } else if (jwtService.estadoToken(token = authHeader) == EstadoToken.CADUCADO) {
                request.setAttribute(ATRIBUTO_TOKEN_CADUCADO, true)
            }
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        /**
         * Granted instead of the role when the account still owes a password
         * change. Not a `ROLE_*` name on purpose: it is a state of the session,
         * not a role of the person, and principle IV defines the four roles as
         * the product's authorisation map.
         */
        const val AUTORIDAD_SOLO_CAMBIO_PASSWORD = "PWD_CHANGE_ONLY"

        /** Request attribute set when the token was rejected for being expired. */
        const val ATRIBUTO_TOKEN_CADUCADO = "granatum.token_acceso_caducado"
    }
}
