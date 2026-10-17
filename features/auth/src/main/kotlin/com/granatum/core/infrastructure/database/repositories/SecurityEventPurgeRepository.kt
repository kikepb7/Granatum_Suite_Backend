package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.SecurityEventEntity
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The only code that deletes security events, and only past their retention
 * period - the same split as timetracking's RetencionPurgaRepository.
 * SecurityEventRepository stays append-only: nothing that writes events can
 * delete them, and this interface can only delete what the cut-off allows.
 */
interface SecurityEventPurgeRepository : Repository<SecurityEventEntity, UUID> {

    @Transactional
    @Modifying
    @Query("DELETE FROM SecurityEventEntity e WHERE e.occurredAt < :cutoff")
    fun deleteOlderThan(@Param("cutoff") cutoff: Instant): Int
}
