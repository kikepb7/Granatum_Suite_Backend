package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.type.EventStatus
import com.granatum.core.infrastructure.database.entities.EventEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

interface EventRepository : JpaRepository<EventEntity, UUID> {

    /**
     * The event, locked until the transaction ends: two changes to the same event (two people
     * assigning material, or the daily job finishing it meanwhile) run one after the other
     * (specs/011-events research.md D-003).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM EventEntity e WHERE e.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): Optional<EventEntity>

    fun findAllByStatusOrderByDateAscNameAsc(status: EventStatus): List<EventEntity>

    fun findAllByStatusOrderByDateDescNameAsc(status: EventStatus): List<EventEntity>

    /** The scheduled events of days already gone: what the daily job finishes. */
    @Query("SELECT e.id FROM EventEntity e WHERE e.status = :status AND e.date < :today")
    fun idsWithStatusBefore(@Param("status") status: EventStatus, @Param("today") today: LocalDate): List<UUID>
}
