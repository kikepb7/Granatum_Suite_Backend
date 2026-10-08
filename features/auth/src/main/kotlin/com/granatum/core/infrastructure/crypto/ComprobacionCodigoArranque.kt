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
class ComprobacionCodigoArranque(
    @param:Value("\${auth.registro.codigo-arranque:}") private val codigo: String
) {

    @PostConstruct
    fun comprobar() {
        val limpio = codigo.trim()
        check(limpio.isEmpty() || limpio.length >= LONGITUD_MINIMA) {
            "AUTH_CODIGO_ARRANQUE es demasiado corto: necesita al menos $LONGITUD_MINIMA caracteres " +
                "(genera uno con `openssl rand -base64 24`) o déjalo vacío para desactivar el arranque."
        }
    }

    companion object {
        const val LONGITUD_MINIMA = 24
    }
}
