package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.type.EstadoEvento
import com.granatum.core.infrastructure.database.entities.EventoEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

interface EventoRepository : JpaRepository<EventoEntity, UUID> {

    /**
     * The event, locked until the transaction ends: two changes to the same event (two people
     * assigning material, or the daily job finishing it meanwhile) run one after the other
     * (specs/011-events research.md D-003).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM EventoEntity e WHERE e.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): Optional<EventoEntity>

    fun findAllByEstadoOrderByFechaAscNombreAsc(estado: EstadoEvento): List<EventoEntity>

    fun findAllByEstadoOrderByFechaDescNombreAsc(estado: EstadoEvento): List<EventoEntity>

    /** The scheduled events of days already gone: what the daily job finishes. */
    @Query("SELECT e.id FROM EventoEntity e WHERE e.estado = :estado AND e.fecha < :hoy")
    fun idsConEstadoAntesDe(@Param("estado") estado: EstadoEvento, @Param("hoy") hoy: LocalDate): List<UUID>
}
