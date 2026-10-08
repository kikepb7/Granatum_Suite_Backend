package com.granatum.core.api.security

import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A token bucket (feature 006, research.md D-001): holds up to [capacidad]
 * tokens and refills continuously at [capacidad] per [periodo]. Each request
 * takes one.
 *
 * Continuous rather than a fixed window, because a fixed window admits twice
 * the quota around its boundary - N at the end of one window and N at the start
 * of the next.
 *
 * Pure and not thread-safe: [LimitadorPorOrigen] serialises access. The clock
 * is passed in, so it is tested without waiting.
 */
class CuboFichas(private val capacidad: Int, periodo: Duration, ahora: Instant) {

    init {
        require(capacidad > 0) { "La capacidad de un cupo tiene que ser positiva" }
        require(!periodo.isNegative && !periodo.isZero) { "El periodo de un cupo tiene que ser positivo" }
    }

    /** Tokens per nanosecond. */
    private val ritmo: Double = capacidad.toDouble() / periodo.toNanos()

    private var fichas: Double = capacidad.toDouble()
    private var ultimaRecarga: Instant = ahora

    /**
     * Takes one token. `null` if there was one; otherwise the seconds until the
     * next one, rounded up and at least 1 - what goes in `Retry-After`.
     */
    fun consumir(ahora: Instant): Long? {
        recargar(ahora)
        if (fichas >= 1.0) {
            fichas -= 1.0
            return null
        }
        val nanosHastaUna = (1.0 - fichas) / ritmo
        return max(1L, ceil(nanosHastaUna / 1_000_000_000.0).toLong())
    }

    private fun recargar(ahora: Instant) {
        // A clock that goes backwards (NTP adjustment) mints nothing.
        if (ahora.isAfter(ultimaRecarga)) {
            val transcurrido = Duration.between(ultimaRecarga, ahora).toNanos()
            fichas = min(capacidad.toDouble(), fichas + transcurrido * ritmo)
            ultimaRecarga = ahora
        }
    }
}
