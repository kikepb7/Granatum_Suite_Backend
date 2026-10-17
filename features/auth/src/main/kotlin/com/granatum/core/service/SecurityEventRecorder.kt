package com.granatum.core.service

import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.database.entities.SecurityEventEntity
import com.granatum.core.infrastructure.database.repositories.SecurityEventRepository
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
 * unknown email, where [accountId] is simply null. See the comment on
 * `SecurityEventEntity` for why, including the deliberate loss of email
 * enumeration detection.
 */
@Service
class SecurityEventRecorder(
    private val repository: SecurityEventRepository,
    private val clock: Clock = Clock.systemUTC()
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(type: SecurityEventType, accountId: EntityId? = null) {
        repository.save(
            SecurityEventEntity(
                accountId = accountId,
                type = type,
                occurredAt = clock.instant()
            )
        )
    }
}
