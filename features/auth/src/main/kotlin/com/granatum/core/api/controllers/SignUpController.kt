package com.granatum.core.api.controllers

import com.granatum.core.api.dto.SignUpRequest
import com.granatum.core.api.dto.SignUpResponse
import com.granatum.core.service.SignUpData
import com.granatum.core.service.SignUpService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Sign-up of the business owner (feature 009): public, but only with the
 * deploy-time bootstrap code and only while the installation has no ADMIN.
 * Everyone else is onboarded by an ADMIN (`POST /api/auth/onboarding`).
 */
@RestController
@RequestMapping("/api/auth/sign-up")
class SignUpController(private val signUpService: SignUpService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun signUp(@Valid @RequestBody request: SignUpRequest): SignUpResponse {
        signUpService.signUp(
            SignUpData(
                email = request.email,
                password = request.password,
                name = request.name,
                identityDocument = request.identityDocument,
                bootstrapCode = request.bootstrapCode
            )
        )
        return SignUpResponse(status = "ACTIVE", message = "Cuenta de administración creada. Ya puedes iniciar sesión.")
    }
}
