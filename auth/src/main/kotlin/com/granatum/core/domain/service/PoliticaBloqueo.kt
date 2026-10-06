package com.granatum.core.domain.service

import com.granatum.core.domain.model.EstadoBloqueo
import java.time.Duration
import java.time.Instant

/**
 * The escalating lockout, as a pure function.
 *
 * Written in Kotlin and not as a SQL `CASE` on purpose. This is the business
 * rule of the feature, and principle V requires business rules to have unit
 * tests that need neither Spring nor a database; expressed in the schema, the
 * four durations would live where no unit test could reach them, or they would
 * have to be duplicated in Kotlin with a third test asserting the two copies
 * agree.
 *
 * The caller applies the result inside a short transaction that re-reads the row
 * under a write lock. The password verification happens **before** that
 * transaction: holding a row lock across the ~110 ms of Argon2 would serialise
 * every attempt against one account and turn this protection into the amplifier
 * of a denial of service.
 */
object PoliticaBloqueo {

    const val FALLOS_PARA_BLOQUEAR = 5
    const val NIVEL_MAXIMO = 4

    /**
     * 1, 5, 15 and 60 minutes. Punishes an honest mistake lightly and makes a
     * sustained attack expensive fast.
     */
    fun duracion(nivel: Int): Duration = when (nivel.coerceAtMost(NIVEL_MAXIMO)) {
        1 -> Duration.ofMinutes(1)
        2 -> Duration.ofMinutes(5)
        3 -> Duration.ofMinutes(15)
        else -> Duration.ofMinutes(60)
    }

    /**
     * A failed sign-in attempt.
     *
     * **If a lockout is already active, nothing changes - the state is returned
     * untouched.** This is FR-016c, and it is load-bearing. Without it, anyone
     * could keep a person locked out indefinitely without ever knowing their
     * password: a free denial of service, and in this product being locked out
     * means being unable to clock in, which opens a gap in a record that carries
     * legal weight.
     *
     * Otherwise the counter rises, and on reaching five the level goes up (capped
     * at four), the deadline is set, **and the counter returns to zero** - so
     * each new lockout needs another five consecutive failures once the previous
     * one has expired, exactly as FR-016c words it. Without that reset the first
     * failure after a lockout expired would re-lock the account immediately.
     */
    fun trasFallo(estado: EstadoBloqueo, ahora: Instant): EstadoBloqueo {
        if (estado.estaBloqueada(ahora)) return estado

        val intentos = estado.intentosFallidos + 1
        if (intentos < FALLOS_PARA_BLOQUEAR) {
            return estado.copy(intentosFallidos = intentos)
        }

        val nivel = (estado.nivel + 1).coerceAtMost(NIVEL_MAXIMO)
        return EstadoBloqueo(
            intentosFallidos = 0,
            nivel = nivel,
            bloqueadaHasta = ahora.plus(duracion(nivel))
        )
    }

    /**
     * A successful sign-in wipes the slate: counter, level and deadline
     * (FR-015, FR-016b). Resetting the **level** is the part that matters -
     * someone who mistyped their password one day should not still be carrying
     * a one-hour penalty weeks later.
     */
    fun trasExito(): EstadoBloqueo = EstadoBloqueo()
}
