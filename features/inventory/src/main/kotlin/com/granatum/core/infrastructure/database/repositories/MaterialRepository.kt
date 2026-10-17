package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.type.Almacen
import com.granatum.core.infrastructure.database.entities.MaterialEntity
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
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

    /**
     * Takes [cantidad] units out only if there are that many: one statement, so two concurrent
     * withdrawals can never leave the stock negative (specs/011-events research.md D-003).
     * Returns the rows changed: 0 when there were not enough units or no such material.
     *
     * Leaves the persistence context alone (no clear): the caller's transaction may be holding
     * an event it is still changing. Read the new amount with [cantidadDe], which goes to the
     * database, not with a cached entity.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        "UPDATE MaterialEntity m SET m.cantidad = m.cantidad - :cantidad, m.fechaUltimaModificacion = :ahora " +
            "WHERE m.id = :id AND m.cantidad >= :cantidad"
    )
    fun retirar(@Param("id") id: UUID, @Param("cantidad") cantidad: Int, @Param("ahora") ahora: Instant): Int

    /** Puts units back. Returns 0 when the material no longer exists. */
    @Modifying(flushAutomatically = true)
    @Query(
        "UPDATE MaterialEntity m SET m.cantidad = m.cantidad + :cantidad, m.fechaUltimaModificacion = :ahora " +
            "WHERE m.id = :id"
    )
    fun devolver(@Param("id") id: UUID, @Param("cantidad") cantidad: Int, @Param("ahora") ahora: Instant): Int

    /** The amount in the database right now, bypassing any cached entity; `null` if it does not exist. */
    @Query("SELECT m.cantidad FROM MaterialEntity m WHERE m.id = :id")
    fun cantidadDe(@Param("id") id: UUID): Int?

    @EntityGraph(attributePaths = ["categoria"])
    fun findAllByIdIn(ids: Collection<UUID>): List<MaterialEntity>
}
