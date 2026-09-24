package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.HistorialMaterialEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HistorialMaterialRepository : JpaRepository<HistorialMaterialEntity, UUID> {
    fun findAllByMaterialIdOrderByFechaDesc(materialId: UUID): List<HistorialMaterialEntity>
}
