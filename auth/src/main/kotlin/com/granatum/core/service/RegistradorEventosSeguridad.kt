package com.granatum.core.service

import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.database.entities.EventoSeguridadEntity
import com.granatum.core.infrastructure.database.repositories.EventoSeguridadRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Writes the security log (FR-017). Inserts, and nothing else.
 *
 * `Clock` is injected with a default, following `RegistradorEventos` in
 * `timetracking`: a test can hand it a fixed clock without a Spring bean having
 * to exist for it.
 *
 * ## Why REQUIRES_NEW
 *
 * A failed sign-in has to leave a record **and** a rolled-back transaction: the
 * attempt failed, so nothing else about it should persist. Joining the caller's
 * transaction would mean the rollback took the log entry with it, and the one
 * trace of a brute-force attempt would be the one thing never written down.
 *
 * ## What never reaches here
 *
 * No password, no token, no email address - not even for an attempt against an
 * unknown email, where [cuentaId] is simply null. See the comment on
 * `EventoSeguridadEntity` for why, including the deliberate loss of email
 * enumeration detection.
 */
@Service
class RegistradorEventosSeguridad(
    private val repository: EventoSeguridadRepository,
    private val clock: Clock = Clock.systemUTC()
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun registrar(tipo: TipoEventoSeguridad, cuentaId: EntityId? = null) {
        repository.save(
            EventoSeguridadEntity(
                cuentaId = cuentaId,
                tipo = tipo,
                ocurridoEn = clock.instant()
            )
        )
    }
}
