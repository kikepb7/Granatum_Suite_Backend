package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.CategoriaEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CategoriaRepository : JpaRepository<CategoriaEntity, UUID> {
    fun existsByNombreIgnoreCase(nombre: String): Boolean
}
