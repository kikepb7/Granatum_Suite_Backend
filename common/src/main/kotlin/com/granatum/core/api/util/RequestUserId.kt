package com.granatum.core.api.util

import com.granatum.core.domain.exception.UnauthorizedException
import com.granatum.core.domain.type.EntityId
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Resolves the authenticated subject (set by [com.granatum.core.api.config.JwtAuthFilter])
 * from the current security context. Throws when called outside an authenticated request.
 */
val requestUserId: EntityId
    get() = SecurityContextHolder.getContext().authentication?.principal as? EntityId
        ?: throw UnauthorizedException()
