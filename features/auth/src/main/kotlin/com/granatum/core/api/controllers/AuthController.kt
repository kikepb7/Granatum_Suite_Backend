package com.granatum.core.api.controllers

import com.granatum.core.api.dto.ChangePasswordRequest
import com.granatum.core.api.dto.LoginRequest
import com.granatum.core.api.dto.LogoutRequest
import com.granatum.core.api.dto.TokenPairResponse
import com.granatum.core.api.dto.RefreshRequest
import com.granatum.core.domain.model.TokenPair
import com.granatum.core.api.util.requestUserId
import com.granatum.core.service.AuthenticationService
import com.granatum.core.service.AccountService
import com.granatum.core.service.SessionService
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
    private val authenticationService: AuthenticationService,
    private val sessionService: SessionService,
    private val accountService: AccountService
) {

    /**
     * Public, because the credential travels in the body rather than in the
     * `Authorization` header - there is nothing to authenticate with yet.
     */
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): TokenPairResponse =
        authenticationService.signIn(
            email = request.email,
            password = request.password
        ).toResponse()

    /**
     * Public for the same reason as login: the refresh token *is* the
     * credential, and it travels in the body.
     */
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): TokenPairResponse =
        sessionService.rotate(request.refreshToken).toResponse()

    /**
     * `204` with no body, and public: the normal moment to log out is after the
     * access token has already expired, so requiring one would make the
     * operation impossible exactly when it is needed.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@Valid @RequestBody request: LogoutRequest) {
        sessionService.close(request.refreshToken)
    }

    /**
     * The only operation a session pending a password change may perform
     * (FR-020).
     *
     * The account is resolved from the **token subject**, never from the body:
     * principle IV forbids accepting a user identifier in the request. The
     * subject is the empleado id, so the account is found through it.
     *
     * Answers with a fresh pair carrying `passwordChangeRequired: false`, so the
     * client can carry on without signing in again.
     */
    @PostMapping("/change-password")
    fun changePassword(@Valid @RequestBody request: ChangePasswordRequest): TokenPairResponse =
        accountService.changeEmployeePassword(
            employeeId = requestUserId,
            currentPassword = request.currentPassword,
            newPassword = request.newPassword
        ).toResponse()
}

internal fun TokenPair.toResponse() = TokenPairResponse(
    accessToken = accessToken,
    refreshToken = refreshToken,
    expiresIn = expiresInSeconds,
    passwordChangeRequired = passwordChangeRequired
)
