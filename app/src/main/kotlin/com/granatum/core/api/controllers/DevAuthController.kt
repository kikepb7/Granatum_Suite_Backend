package com.granatum.core.api.controllers

import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Dev-only convenience endpoint to mint a JWT without a real auth/login flow
 * (that will arrive with the `timetracking`/`Empleado` module). Never active
 * outside the `dev` profile.
 */
@RestController
@RequestMapping("/api/dev")
@Profile("dev")
class DevAuthController(
    private val jwtService: JwtService
) {

    @PostMapping("/token")
    fun issueToken(
        @RequestParam(required = false) subject: UUID?,
        @RequestParam(required = false, defaultValue = "ADMIN") role: Role
    ): Map<String, String> {
        val subjectId = subject ?: UUID.randomUUID()
        return mapOf(
            "subject" to subjectId.toString(),
            "role" to role.name,
            "accessToken" to jwtService.generateAccessToken(subjectId, role),
            "refreshToken" to jwtService.generateRefreshToken(subjectId, role)
        )
    }
}
