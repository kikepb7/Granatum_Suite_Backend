package com.granatum.core.domain.model

import java.time.Instant

/**
 * How close an account is to being locked, and until when.
 *
 * Immutable: every transition returns a new value rather than mutating this
 * one, which is what lets [com.granatum.core.domain.service.PoliticaBloqueo] be
 * a pure function and therefore unit-testable without a database (principle V).
 */
data class EstadoBloqueo(
    val intentosFallidos: Int = 0,
    val nivel: Int = 0,
    val bloqueadaHasta: Instant? = null
) {
    /**
     * Strictly `after`, so the instant the lockout expires the account is
     * already usable. One second of extra lockout would be harmless; the reason
     * to be precise is that FR-016c's test compares an unchanged state exactly.
     */
    fun estaBloqueada(ahora: Instant): Boolean =
        bloqueadaHasta != null && bloqueadaHasta.isAfter(ahora)
}
