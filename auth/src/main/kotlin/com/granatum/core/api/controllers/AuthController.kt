package com.granatum.core.api.controllers

import com.granatum.core.api.dto.CambioPasswordRequest
import com.granatum.core.api.dto.LoginRequest
import com.granatum.core.api.dto.LogoutRequest
import com.granatum.core.api.dto.ParTokensResponse
import com.granatum.core.api.dto.RefreshRequest
import com.granatum.core.domain.model.ParTokens
import com.granatum.core.api.util.requestUserId
import com.granatum.core.service.AutenticacionService
import com.granatum.core.service.CuentaAccesoService
import com.granatum.core.service.SesionService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `/api/auth/...`. Controller -> Service -> Repository, with no business logic
 * here and no error bodies built by hand (principle VIII): every failure leaves
 * as an exception and `AuthExceptionHandler` turns it into the single
 * `{code, message}` shape.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val autenticacionService: AutenticacionService,
    private val sesionService: SesionService,
    private val cuentaAccesoService: CuentaAccesoService
) {

    /**
     * Public, because the credential travels in the body rather than in the
     * `Authorization` header - there is nothing to authenticate with yet.
     */
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ParTokensResponse =
        autenticacionService.iniciarSesion(
            email = request.email,
            password = request.password
        ).toResponse()

    /**
     * Public for the same reason as login: the refresh token *is* the
     * credential, and it travels in the body.
     */
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): ParTokensResponse =
        sesionService.rotar(request.refreshToken).toResponse()

    /**
     * `204` with no body, and public: the normal moment to log out is after the
     * access token has already expired, so requiring one would make the
     * operation impossible exactly when it is needed.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@Valid @RequestBody request: LogoutRequest) {
        sesionService.cerrar(request.refreshToken)
    }

    /**
     * The only operation a session pending a password change may perform
     * (FR-020).
     *
     * The account is resolved from the **token subject**, never from the body:
     * principle IV forbids accepting a user identifier in the request. The
     * subject is the empleado id, so the account is found through it.
     *
     * Answers with a fresh pair carrying `requiereCambioPassword: false`, so the
     * client can carry on without signing in again.
     */
    @PostMapping("/change-password")
    fun cambiarPassword(@Valid @RequestBody request: CambioPasswordRequest): ParTokensResponse =
        cuentaAccesoService.cambiarPasswordDeEmpleado(
            empleadoId = requestUserId,
            actual = request.passwordActual,
            nueva = request.passwordNueva
        ).toResponse()
}

internal fun ParTokens.toResponse() = ParTokensResponse(
    accessToken = accessToken,
    refreshToken = refreshToken,
    expiresIn = expiresInSegundos,
    requiereCambioPassword = requiereCambioPassword
)
