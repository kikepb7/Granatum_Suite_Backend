package com.granatum.core.service

import com.granatum.core.domain.event.DomainNotice
import com.granatum.core.infrastructure.database.repositories.NotificationRepository
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
 * - **fallbackExecution**: a publisher with no transaction (feature 005's
 *   sign-up was one; none is today) would otherwise silently never be heard.
 *   Kept so the next one does not have to rediscover it.
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
 * anyway. With the transaction in [NotificationWriter], it is opened and closed -
 * or rolled back - inside the `try`.
 */
@Component
class NotificationNoticeListener(private val writer: NotificationWriter) {

    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onNotice(notice: DomainNotice) {
        try {
            writer.save(notice)
        } catch (e: Exception) {
            log.warn("No se pudo crear la notificación de tipo {}: {}", notice.type, e.javaClass.simpleName)
        }
    }
}

/**
 * One notice per recipient, in a transaction of its own: after commit, the
 * original transaction is finished but still bound to the thread.
 */
@Component
class NotificationWriter(
    private val recipients: NotificationRecipients,
    private val notifications: NotificationRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun save(notice: DomainNotice) {
        val now = clock.instant()
        recipients.of(notice).forEach { recipient ->
            notifications.insertIfAbsent(UUID.randomUUID(), recipient, notice.type.name, notice.referenceId, now)
        }
    }
}
