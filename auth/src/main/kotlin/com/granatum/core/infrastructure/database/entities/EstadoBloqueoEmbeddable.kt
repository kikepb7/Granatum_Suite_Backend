package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.time.Instant

/**
 * The three columns that govern the escalating lockout, kept as one piece.
 *
 * They are embedded rather than left as three loose fields because they only
 * mean anything together, and every transition writes all three at once. Loose,
 * they invite the one mistake that silently breaks FR-016a: setting
 * `bloqueadaHasta` without touching `nivelBloqueo`, after which every lockout
 * lasts one minute forever and no test of a single lockout would notice.
 *
 * The logic that computes the next value lives in
 * [com.granatum.core.domain.service.PoliticaBloqueo], a pure object with no
 * Spring and no database, because that is the business rule of this feature and
 * principle V requires it to be unit-testable.
 */
@Embeddable
class EstadoBloqueoEmbeddable(
    /**
     * Consecutive failures since the last success or lockout. Reset to 0 when a
     * lockout is applied, so each new lockout needs another five failures
     * (FR-016c).
     */
    @Column(name = "intentos_fallidos", nullable = false)
    var intentosFallidos: Short = 0,

    /**
     * Survives the expiry of the lockout - it is what makes the next one last
     * five minutes instead of one. 0 means never locked; capped at 4.
     */
    @Column(name = "nivel_bloqueo", nullable = false)
    var nivelBloqueo: Short = 0,

    /** `null` = not locked. The lockout lifts by comparison with the clock. */
    @Column(name = "bloqueada_hasta")
    var bloqueadaHasta: Instant? = null
)
