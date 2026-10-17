package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.RetentionPurgeEntity
import org.springframework.data.repository.Repository
import java.util.UUID

/**
 * The purge audit log. Append-only and never itself purged, so it exposes an
 * insert and a read and nothing else.
 */
interface RetentionPurgeRepository : Repository<RetentionPurgeEntity, UUID> {
    fun save(purgeRecord: RetentionPurgeEntity): RetentionPurgeEntity
    fun findAllByOrderByExecutedAtDesc(): List<RetentionPurgeEntity>
}
