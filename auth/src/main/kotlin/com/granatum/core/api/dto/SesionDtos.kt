package com.granatum.core.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * The refresh token is the credential for both of these, so it travels in the
 * body rather than in the `Authorization` header.
 *
 * Capped at 128: the generated value is 43 characters of base64url, and a cap
 * stops a multi-megabyte body from reaching the SHA-256 and the database lookup.
 * No minimum and no format check - an unknown token and a malformed one must
 * both be rejected the same way, and validating the shape here would answer
 * `400` to one and `401` to the other, which tells an attacker their guess was
 * at least well-formed.
 */
data class RefreshRequest(
    @field:NotBlank
    @field:Size(max = 128)
    val refreshToken: String = ""
) {
    /**
     * Never prints the secret fields. At DEBUG, Spring MVC logs every request
     * and response body through `toString()` ("Read application/json to [...]"),
     * and a `data class` prints every field by default - which put passwords in
     * clear into the log until `SinDatosPersonalesEnLogsIT` caught it.
     */
    override fun toString(): String =
        "RefreshRequest(refreshToken=***)"
}

data class LogoutRequest(
    @field:NotBlank
    @field:Size(max = 128)
    val refreshToken: String = ""
) {
    /**
     * Never prints the secret fields. At DEBUG, Spring MVC logs every request
     * and response body through `toString()` ("Read application/json to [...]"),
     * and a `data class` prints every field by default - which put passwords in
     * clear into the log until `SinDatosPersonalesEnLogsIT` caught it.
     */
    override fun toString(): String =
        "LogoutRequest(refreshToken=***)"
}
