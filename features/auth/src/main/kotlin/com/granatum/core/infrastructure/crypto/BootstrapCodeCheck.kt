package com.granatum.core.infrastructure.crypto

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Refuses to start with a guessable bootstrap code (feature 006, FR-010,
 * research.md D-006).
 *
 * The code creates the first ADMIN of an installation. With the sign-up quota
 * (5 per hour per address) a 24-character random code cannot be guessed; one
 * like "granatum2026" can. Empty is fine: it means bootstrap is disabled.
 *
 * The message names the variable and never repeats the value - it ends up in a
 * startup log.
 */
@Component
class BootstrapCodeCheck(
    @param:Value("\${auth.sign-up.bootstrap-code:}") private val code: String
) {

    @PostConstruct
    fun verify() {
        val trimmed = code.trim()
        check(trimmed.isEmpty() || trimmed.length >= MIN_LENGTH) {
            "AUTH_BOOTSTRAP_CODE es demasiado corto: necesita al menos $MIN_LENGTH caracteres " +
                "(genera uno con `openssl rand -base64 24`) o déjalo vacío para desactivar el arranque."
        }
    }

    companion object {
        const val MIN_LENGTH = 24
    }
}
