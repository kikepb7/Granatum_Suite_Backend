package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.type.Almacen
import com.granatum.core.infrastructure.database.entities.MaterialEntity
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

/**
 * The reads fetch the category with the material. The service maps to the
 * model outside any transaction (so that signing images never holds a
 * connection, research.md D-003), where a lazy category could no longer load,
 * and a listing would otherwise issue one query per material.
 */
interface MaterialRepository : JpaRepository<MaterialEntity, UUID> {

    @EntityGraph(attributePaths = ["categoria"])
    override fun findAll(): List<MaterialEntity>

    @EntityGraph(attributePaths = ["categoria"])
    override fun findById(id: UUID): Optional<MaterialEntity>

    fun findAllByCategoriaId(categoriaId: UUID): List<MaterialEntity>

    @EntityGraph(attributePaths = ["categoria"])
    fun findAllByLocalizacionAlmacen(almacen: Almacen): List<MaterialEntity>

    @EntityGraph(attributePaths = ["categoria"])
    fun findAllByLocalizacionAlmacenAndLocalizacionSeccion(almacen: Almacen, seccion: Int): List<MaterialEntity>
}
