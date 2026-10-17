package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.NotificationEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Notices (feature 008). Every method that reads or marks takes the recipient,
 * so no query can reach someone else's notice by id alone (FR-010).
 */
interface NotificationRepository : Repository<NotificationEntity, UUID> {

    /** FR-007: the same (recipient, type, reference) only once (D-002). Returns 1 if inserted. */
    @Modifying
    @Query(
        value = """
            INSERT INTO notifications (id, recipient_id, type, reference_id, created_at)
            VALUES (:id, :recipient, :type, :reference, :now)
            ON CONFLICT (recipient_id, type, reference_id) DO NOTHING
        """,
        nativeQuery = true
    )
    fun insertIfAbsent(
        @Param("id") id: UUID,
        @Param("recipient") recipient: UUID,
        @Param("type") type: String,
        @Param("reference") reference: UUID,
        @Param("now") now: Instant
    ): Int

    fun findAllByRecipientIdOrderByCreatedAtDescIdDesc(recipientId: UUID, page: Pageable): List<NotificationEntity>

    fun findAllByRecipientIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(recipientId: UUID, page: Pageable): List<NotificationEntity>

    fun countByRecipientIdAndReadAtIsNull(recipientId: UUID): Long

    @Modifying
    @Query("UPDATE NotificationEntity n SET n.readAt = :now WHERE n.id = :id AND n.recipientId = :recipient AND n.readAt IS NULL")
    fun markRead(@Param("id") id: UUID, @Param("recipient") recipient: UUID, @Param("now") now: Instant): Int

    fun existsByIdAndRecipientId(id: UUID, recipientId: UUID): Boolean

    @Modifying
    @Query("UPDATE NotificationEntity n SET n.readAt = :now WHERE n.recipientId = :recipient AND n.readAt IS NULL")
    fun markAllRead(@Param("recipient") recipient: UUID, @Param("now") now: Instant): Int

    /** D-005: read ones older than [readBefore], and any older than [allBefore]. */
    @Modifying
    @Query(
        """
        DELETE FROM NotificationEntity n
         WHERE (n.readAt IS NOT NULL AND n.createdAt < :readBefore)
            OR n.createdAt < :allBefore
        """
    )
    fun cleanUp(@Param("readBefore") readBefore: Instant, @Param("allBefore") allBefore: Instant): Int
}
