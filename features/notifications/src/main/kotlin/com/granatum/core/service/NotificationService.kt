package com.granatum.core.service

import com.granatum.core.domain.exception.NotificationNotFoundException
import com.granatum.core.domain.model.Notification
import com.granatum.core.domain.type.EntityId
import com.granatum.core.infrastructure.database.repositories.NotificationRepository
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * A person's inbox (feature 008, FR-009, FR-010). Every operation takes the
 * recipient from the token subject; the repository never reaches a notice by
 * id alone.
 */
@Service
class NotificationService(
    private val notifications: NotificationRepository,
    private val clock: Clock = Clock.systemUTC()
) {

    @Transactional(readOnly = true)
    fun inbox(recipient: EntityId, unreadOnly: Boolean): List<Notification> {
        val page = Pageable.ofSize(MAX_SIZE)
        val rows = if (unreadOnly) {
            notifications.findAllByRecipientIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(recipient, page)
        } else {
            notifications.findAllByRecipientIdOrderByCreatedAtDescIdDesc(recipient, page)
        }
        return rows.map { Notification(it.id, it.type, it.referenceId, it.createdAt, it.readAt) }
    }

    @Transactional(readOnly = true)
    fun unreadCount(recipient: EntityId): Long = notifications.countByRecipientIdAndReadAtIsNull(recipient)

    /** Marking one already read is fine and changes nothing; someone else's is not found. */
    @Transactional
    fun markRead(id: EntityId, recipient: EntityId) {
        if (notifications.markRead(id, recipient, clock.instant()) == 0 &&
            !notifications.existsByIdAndRecipientId(id, recipient)
        ) {
            throw NotificationNotFoundException(id)
        }
    }

    @Transactional
    fun markAllRead(recipient: EntityId): Int = notifications.markAllRead(recipient, clock.instant())

    companion object {
        /** FR-009: an inbox, not an archive. */
        const val MAX_SIZE = 100
    }
}
