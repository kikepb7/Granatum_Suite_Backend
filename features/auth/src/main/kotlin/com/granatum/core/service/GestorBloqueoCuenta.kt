package com.granatum.core.service

import com.granatum.core.domain.service.PoliticaBloqueo
import com.granatum.core.domain.type.EntityId
import com.granatum.core.infrastructure.database.mappers.aplicar
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.CuentaAccesoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Applies the lockout transitions, each in a **short** transaction.
 *
 * ## Why this is a separate bean
 *
 * Not for tidiness: Spring's transaction proxy does not intercept a call made
 * on `this`, so a `@Transactional` method invoked from elsewhere inside
 * [AutenticacionService] would run with no transaction at all - silently, and
 * without the row lock these transitions depend on.
 *
 * ## Why the transaction has to be short
 *
 * `AutenticacionService` verifies the password **before** calling in here, and
 * deliberately outside any transaction. Holding a row lock across the ~110 ms of
 * Argon2 would serialise every attempt against one account, so an attacker
 * hammering one address would make that person's own sign-ins queue behind
 * theirs - turning the hardening into the amplifier of a denial of service,
 * which is precisely what FR-016c prevents by another route.
 *
 * ## Why no security event is written in here
 *
 * `RegistradorEventosSeguridad` writes in a `REQUIRES_NEW` transaction, so
 * calling it from inside this one would make every thread hold **two**
 * connections at once: one for the row lock and one for the event. With N
 * threads past the hash semaphore and a pool smaller than 2N, each holds one
 * and waits for a second that nobody will release - a pool deadlock that lasts
 * until the connection timeout. That is not a hypothesis: 20 concurrent failed
 * sign-ins against a pool of 10 hung exactly that way.
 *
 * So this class returns what happened and the caller - which holds no
 * transaction - records it. The cost is that the counter and the event are no
 * longer written atomically: a crash in the microseconds between them would
 * lose the event. That is a far better trade than an outage under load, and the
 * counter is the part that must not be lost.
 *
 * ## The accepted window
 *
 * Between the unlocked read that decides the fast rejection and this
 * transaction, one extra attempt can slip through against an account that just
 * became locked. It changes nothing: that attempt is refused all the same, and
 * FR-016c stops it from extending the lockout. What matters - that five
 * simultaneous failures end in a lockout rather than in none - is what
 * `BloqueoConcurrenteIT` asserts.
 */
@Service
class GestorBloqueoCuenta(
    private val cuentas: CuentaAccesoRepository,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * Counts one failed attempt, locking the account if this was the fifth.
     *
     * The row is re-read under `PESSIMISTIC_WRITE`: two simultaneous failures
     * both reading `intentos_fallidos = 4` and both writing `5` would leave the
     * account unlocked, which is the one outcome the protection cannot allow.
     *
     * @return `true` if this attempt is what locked it.
     */
    @Transactional
    fun registrarFallo(cuentaId: EntityId): Boolean {
        val cuenta = cuentas.findWithLockById(cuentaId).orElse(null) ?: return false

        val antes = cuenta.estadoBloqueo.toModel()
        val despues = PoliticaBloqueo.trasFallo(antes, clock.instant())
        cuenta.estadoBloqueo.aplicar(despues)
        cuentas.save(cuenta)

        return antes.bloqueadaHasta != despues.bloqueadaHasta && despues.bloqueadaHasta != null
    }

    /**
     * Wipes the slate after a successful sign-in: counter, level and deadline
     * (FR-015, FR-016b).
     *
     * Resetting the **level** is the part that matters. Someone who mistyped
     * their password one morning should not still be serving a one-hour lockout
     * weeks later for the next honest mistake.
     */
    @Transactional
    fun registrarExito(cuentaId: EntityId) {
        val cuenta = cuentas.findWithLockById(cuentaId).orElse(null) ?: return

        val antes = cuenta.estadoBloqueo.toModel()
        // Skip the write when there is nothing to clear: the common case is a
        // correct password on the first try, and an UPDATE on every sign-in
        // would churn `updated_at` for no reason.
        if (antes == PoliticaBloqueo.trasExito()) return

        cuenta.estadoBloqueo.aplicar(PoliticaBloqueo.trasExito())
        cuentas.save(cuenta)
    }
}
