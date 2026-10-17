package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.type.EstadoEvento
import com.granatum.core.infrastructure.database.entities.EventoMaterialEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface EventoMaterialRepository : JpaRepository<EventoMaterialEntity, UUID> {

    fun findAllByEventoId(eventoId: UUID): List<EventoMaterialEntity>

    fun findAllByEventoIdIn(eventoIds: Collection<UUID>): List<EventoMaterialEntity>

    fun findByEventoIdAndMaterialId(eventoId: UUID, materialId: UUID): EventoMaterialEntity?

    /** Whether the material is out at any event in [estado] (UsoMateriales, FR-013). */
    @Query(
        "SELECT COUNT(l) > 0 FROM EventoMaterialEntity l, EventoEntity e " +
            "WHERE e.id = l.eventoId AND l.materialId = :materialId AND e.estado = :estado"
    )
    fun usadoEnEstado(@Param("materialId") materialId: UUID, @Param("estado") estado: EstadoEvento): Boolean
}
