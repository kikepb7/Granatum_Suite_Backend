package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.event.NoticeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A notice for one recipient (feature 008, V23, V33). Written through the repository's INSERT … ON CONFLICT. */
@Entity
@Table(name = "notifications")
class NotificationEntity(
    @Id
    val id: UUID,

    @Column(name = "recipient_id", nullable = false)
    val recipientId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 24)
    val type: NoticeType,

    @Column(name = "reference_id", nullable = false)
    val referenceId: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "read_at")
    var readAt: Instant?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NotificationEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String = "NotificationEntity(id=$id, type=$type)"
}
