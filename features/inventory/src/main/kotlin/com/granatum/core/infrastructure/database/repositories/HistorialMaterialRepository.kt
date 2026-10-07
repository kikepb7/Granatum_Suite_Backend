package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.HistorialMaterialEntity
import org.springframework.data.repository.Repository
import java.util.UUID

/**
 * Deliberately extends [Repository] and not `JpaRepository`.
 *
 * `historial_material` is an append-only audit table: the application inserts
 * rows and reads them, and never updates or deletes one. `JpaRepository` would
 * bring `delete` and `deleteAll` in by inheritance - and while nothing called
 * them, constitution principle III (v2.0.0) requires repositories of
 * append-only tables not to offer them at all: not calling a method is not
 * enough, because an interface that offers one will eventually be used by
 * accident and there would be nothing to stop it.
 *
 * This closes the debt the amendment declared under Governance.
 */
interface HistorialMaterialRepository : Repository<HistorialMaterialEntity, UUID> {
    fun save(historial: HistorialMaterialEntity): HistorialMaterialEntity
    fun findAllByMaterialIdOrderByFechaDesc(materialId: UUID): List<HistorialMaterialEntity>
}
