package com.granatum.core.service

import com.granatum.core.domain.event.AvisoDominio
import com.granatum.core.infrastructure.database.repositories.NotificacionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Clock
import java.util.UUID

/**
 * Turns the features' domain events into notices (feature 008, research D-001).
 *
 * - **After commit**: a request that rolled back produces no notice of
 *   something that never happened (FR-008).
 * - **fallbackExecution**: some publishers have no transaction - sign-up hashes
 *   the password without holding a connection - and without this the listener
 *   would silently never run for them.
 * - **Failures stop here**: the operation that caused the notice is already
 *   committed, and a lost notice must not become an error for whoever made the
 *   request. After a commit Spring already contains a listener's exception;
 *   with no transaction (fallbackExecution) the listener runs inside
 *   `publishEvent` and nothing else would stop it. The log line carries the type and the exception class, never an
 *   id that ties a person to an event (principle VI).
 *
 * ## Why the writing is in another bean
 *
 * If this method were itself `@Transactional`, a failing statement would mark
 * the transaction rollback-only, and the `catch` below would not be the end of
 * it: committing on the way out would throw `UnexpectedRollbackException`
 * anyway. With the transaction in [EscritorAvisos], it is opened and closed -
 * or rolled back - inside the `try`.
 */
@Component
class OyenteAvisos(private val escritor: EscritorAvisos) {

    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun alOcurrir(aviso: AvisoDominio) {
        try {
            escritor.guardar(aviso)
        } catch (e: Exception) {
            log.warn("No se pudo crear la notificación de tipo {}: {}", aviso.tipo, e.javaClass.simpleName)
        }
    }
}

/**
 * One notice per recipient, in a transaction of its own: after commit, the
 * original transaction is finished but still bound to the thread.
 */
@Component
class EscritorAvisos(
    private val destinatarios: Destinatarios,
    private val notificaciones: NotificacionRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun guardar(aviso: AvisoDominio) {
        val ahora = clock.instant()
        destinatarios.de(aviso).forEach { destinatario ->
            notificaciones.insertarSiNoExiste(UUID.randomUUID(), destinatario, aviso.tipo.name, aviso.referenciaId, ahora)
        }
    }
}
