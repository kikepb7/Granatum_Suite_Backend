package com.granatum.core.domain.model

/**
 * What a successful sign-in or renewal hands back.
 *
 * [expiresInSegundos] is the access token's remaining lifetime, so a client can
 * schedule its renewal without parsing the JWT. [requiereCambioPassword] is
 * here because US4 scenario 2 requires the response to say so: a client that
 * had to discover it by being refused everywhere could not show the right
 * screen.
 *
 * [refreshToken] is the **opaque** value, the only moment it exists in clear.
 * What gets stored is its SHA-256 (FR-011).
 */
data class ParTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSegundos: Long,
    val requiereCambioPassword: Boolean
)
