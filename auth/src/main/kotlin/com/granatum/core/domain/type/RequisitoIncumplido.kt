package com.granatum.core.domain.type

/**
 * Which password-policy requirement a rejected password failed (FR-023).
 *
 * Identifiers and not sentences, because the error body carries them to a
 * client that may want to mark the offending fields, and because a message is
 * for people while a code has to be stable. Crucially, none of these values can
 * contain a fragment of the password - the policy has to explain what is wrong
 * without ever echoing what was typed.
 */
enum class RequisitoIncumplido {
    LONGITUD_MINIMA,
    LONGITUD_MAXIMA,
    FALTA_MAYUSCULA,
    FALTA_MINUSCULA,
    FALTA_DIGITO,
    FALTA_SIMBOLO
}
