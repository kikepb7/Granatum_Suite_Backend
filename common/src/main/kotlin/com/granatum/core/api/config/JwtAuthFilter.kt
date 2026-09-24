package com.granatum.core.api.config

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
                val authorities = listOf(SimpleGrantedAuthority("ROLE_${role.name}"))
                val auth = UsernamePasswordAuthenticationToken(subject, null, authorities)
                SecurityContextHolder.getContext().authentication = auth
            }
        }
        filterChain.doFilter(request, response)
    }
}
