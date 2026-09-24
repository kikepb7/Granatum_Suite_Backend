package com.granatum.core.api.util

import com.granatum.core.domain.exception.UnauthorizedException
import com.granatum.core.domain.type.Role
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Resolves the authenticated subject's [Role] from its `ROLE_*` authority,
 * set by [com.granatum.core.api.config.JwtAuthFilter].
 */
val requestUserRole: Role
    get() {
        val authority = SecurityContextHolder.getContext().authentication?.authorities
            ?.firstOrNull()?.authority
            ?: throw UnauthorizedException()
        return Role.valueOf(authority.removePrefix("ROLE_"))
    }
