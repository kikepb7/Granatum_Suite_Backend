package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.MaterialEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MaterialRepository : JpaRepository<MaterialEntity, UUID> {
    fun findAllByCategoriaId(categoriaId: UUID): List<MaterialEntity>
}
