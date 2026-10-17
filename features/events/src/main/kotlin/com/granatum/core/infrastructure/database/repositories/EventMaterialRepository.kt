package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.type.EventStatus
import com.granatum.core.infrastructure.database.entities.EventMaterialEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface EventMaterialRepository : JpaRepository<EventMaterialEntity, UUID> {

    fun findAllByEventId(eventId: UUID): List<EventMaterialEntity>

    fun findAllByEventIdIn(eventIds: Collection<UUID>): List<EventMaterialEntity>

    fun findByEventIdAndMaterialId(eventId: UUID, materialId: UUID): EventMaterialEntity?

    /** Whether the material is out at any event in [status] (MaterialUsage, FR-013). */
    @Query(
        "SELECT COUNT(l) > 0 FROM EventMaterialEntity l, EventEntity e " +
            "WHERE e.id = l.eventId AND l.materialId = :materialId AND e.status = :status"
    )
    fun usedInStatus(@Param("materialId") materialId: UUID, @Param("status") status: EventStatus): Boolean
}
