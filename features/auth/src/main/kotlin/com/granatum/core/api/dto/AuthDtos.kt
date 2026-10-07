package com.granatum.core.api.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Entry and exit contracts for `/api/auth/...`. Separate from the JPA entities
 * (principle VIII): an entity must never leave over HTTP, and a DTO must never
 * reach the persistence layer.
 *
 * Validated at the edge with `@Valid` in the controller, so a malformed body is
 * a `400 VALIDACION` and never reaches the service.
 */

data class LoginRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String = "",

    /**
     * Capped at 128 to match `PoliticaPassword.LONGITUD_MAXIMA`. The cap is not
     * cosmetic: without it a multi-megabyte body would become Argon2 work, which
     * costs memory and not just CPU on a public endpoint.
     *
     * The minimum is **not** validated here. A sign-in attempt with a short
     * password must be rejected as invalid credentials like any other, not as a
     * policy violation - telling the attacker that the stored password is at
     * least eight characters is free information, and telling a legitimate user
     * their old short password is "invalid" rather than "wrong" is confusing.
     */
    @field:NotBlank
    @field:Size(max = 128)
    val password: String = ""
) {
    /**
     * Never prints the secret fields. At DEBUG, Spring MVC logs every request
     * and response body through `toString()` ("Read application/json to [...]"),
     * and a `data class` prints every field by default - which put passwords in
     * clear into the log until `SinDatosPersonalesEnLogsIT` caught it.
     */
    override fun toString(): String =
        "LoginRequest(email=***, password=***)"
}

/**
 * [expiresIn] is seconds, so a client can schedule its renewal without parsing
 * the JWT. [requiereCambioPassword] is here because US4 scenario 2 requires the
 * response to say so: a client that had to discover it by being refused
 * everywhere could not show the right screen.
 *
 * [refreshToken] is the opaque value, and this response is the only place it
 * ever exists in clear - what gets stored is its SHA-256 (FR-011).
 */
data class ParTokensResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val requiereCambioPassword: Boolean
) {
    /**
     * Never prints the secret fields. At DEBUG, Spring MVC logs every request
     * and response body through `toString()` ("Read application/json to [...]"),
     * and a `data class` prints every field by default - which put passwords in
     * clear into the log until `SinDatosPersonalesEnLogsIT` caught it.
     */
    override fun toString(): String =
        "ParTokensResponse(accessToken=***, refreshToken=***, expiresIn=$expiresIn, " +
            "requiereCambioPassword=$requiereCambioPassword)"
}
