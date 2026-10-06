package com.granatum.core.domain.type

/**
 * Why a JWT was rejected, which is a different question from whether it was.
 *
 * FR-006 requires an expired access token to be rejected **distinguishably**
 * from an invalid one, so that the application knows to renew instead of asking
 * for the password again. Collapsing both into "not valid" is what made that
 * impossible before: `JwtService` caught every exception and returned `null`, so
 * from the inside the two cases were already the same thing long before the HTTP
 * layer had a chance to tell them apart.
 */
enum class EstadoToken {
    VALIDO,

    /** Signature checks out, but the token's lifetime has elapsed. */
    CADUCADO,

    /** Unreadable, tampered with, or signed with another key. */
    INVALIDO
}
