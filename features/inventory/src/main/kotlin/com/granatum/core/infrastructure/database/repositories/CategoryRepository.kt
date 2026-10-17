package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.CategoryEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CategoryRepository : JpaRepository<CategoryEntity, UUID> {
    fun existsByNameIgnoreCase(name: String): Boolean
}
