package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.type.Warehouse
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

    @EntityGraph(attributePaths = ["category"])
    override fun findAll(): List<MaterialEntity>

    @EntityGraph(attributePaths = ["category"])
    override fun findById(id: UUID): Optional<MaterialEntity>

    fun findAllByCategoryId(categoryId: UUID): List<MaterialEntity>

    @EntityGraph(attributePaths = ["category"])
    fun findAllByLocationWarehouse(warehouse: Warehouse): List<MaterialEntity>

    @EntityGraph(attributePaths = ["category"])
    fun findAllByLocationWarehouseAndLocationSection(warehouse: Warehouse, section: Int): List<MaterialEntity>

    /**
     * Takes [quantity] units out only if there are that many: one statement, so two concurrent
     * withdrawals can never leave the stock negative (specs/011-events research.md D-003).
     * Returns the rows changed: 0 when there were not enough units or no such material.
     *
     * Leaves the persistence context alone (no clear): the caller's transaction may be holding
     * an event it is still changing. Read the new amount with [quantityOf], which goes to the
     * database, not with a cached entity.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        "UPDATE MaterialEntity m SET m.quantity = m.quantity - :quantity, m.updatedAt = :now " +
            "WHERE m.id = :id AND m.quantity >= :quantity"
    )
    fun withdraw(@Param("id") id: UUID, @Param("quantity") quantity: Int, @Param("now") now: Instant): Int

    /** Puts units back. Returns 0 when the material no longer exists. */
    @Modifying(flushAutomatically = true)
    @Query(
        "UPDATE MaterialEntity m SET m.quantity = m.quantity + :quantity, m.updatedAt = :now " +
            "WHERE m.id = :id"
    )
    fun putBack(@Param("id") id: UUID, @Param("quantity") quantity: Int, @Param("now") now: Instant): Int

    /** The amount in the database right now, bypassing any cached entity; `null` if it does not exist. */
    @Query("SELECT m.quantity FROM MaterialEntity m WHERE m.id = :id")
    fun quantityOf(@Param("id") id: UUID): Int?

    @EntityGraph(attributePaths = ["category"])
    fun findAllByIdIn(ids: Collection<UUID>): List<MaterialEntity>
}
