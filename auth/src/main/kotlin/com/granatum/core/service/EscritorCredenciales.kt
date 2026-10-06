package com.granatum.core.service

import com.granatum.core.domain.exception.CuentaNoEncontradaException
import com.granatum.core.domain.service.PoliticaBloqueo
import com.granatum.core.domain.type.EntityId
import com.granatum.core.infrastructure.database.entities.CuentaAccesoEntity
import com.granatum.core.infrastructure.database.mappers.aplicar
import com.granatum.core.infrastructure.database.repositories.CuentaAccesoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Writes a new credential onto an account, in one short locked transaction.
 *
 * ## Why this exists
 *
 * Both a password change and a reset compute an Argon2 hash first - ~110 ms,
 * deliberately outside any transaction - and then write it. The first version
 * wrote it by calling `save` on the entity loaded **before** the hash. That
 * entity is detached by then, and `save` on a detached entity merges *every*
 * column from that stale copy: if a failed sign-in had bumped the lockout
 * counter during those 110 ms, the change silently reverted it. A lost update,
 * on exactly the columns the brute-force protection depends on.
 *
 * So the write re-reads the row under `PESSIMISTIC_WRITE` and changes only what
 * it means to change. It is a separate bean for the same reason
 * `GestorBloqueoCuenta` is: Spring's proxy does not intercept a call on `this`,
 * so a `@Transactional` method inside the calling service would run with no
 * transaction at all.
 */
@Service
class EscritorCredenciales(
    private val cuentas: CuentaAccesoRepository
) {

    /**
     * @param hash already computed by the caller, outside any transaction.
     * @param requiereCambio `false` after the person chooses their own
     * password, `true` after a reset hands out a temporary one.
     * @param levantarBloqueo `true` on reset (FR-026): an `ADMIN` resetting the
     * password of somebody who locked themselves out must not leave them still
     * locked.
     */
    @Transactional
    fun aplicar(
        cuentaId: EntityId,
        hash: String,
        requiereCambio: Boolean,
        levantarBloqueo: Boolean
    ): CuentaAccesoEntity {
        val cuenta = cuentas.findWithLockById(cuentaId).orElseThrow { CuentaNoEncontradaException() }

        // The hash and the flag only ever change as a pair. A new hash without
        // the flag would let a temporary password become permanent; the flag
        // without a new hash would trap the person on the change screen.
        cuenta.passwordHash = hash
        cuenta.requiereCambioPassword = requiereCambio

        if (levantarBloqueo) {
            // All three lockout columns at once, through the same mapper the
            // lockout transitions use - never one at a time.
            cuenta.estadoBloqueo.aplicar(PoliticaBloqueo.trasExito())
        }

        return cuentas.save(cuenta)
    }
}
