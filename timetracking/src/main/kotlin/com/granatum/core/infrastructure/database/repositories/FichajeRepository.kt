package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.infrastructure.database.entities.FichajeEntity
import org.springframework.data.repository.Repository
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Deliberately extends [Repository] and not `JpaRepository`, so no deletion
 * operation exists on the normal path (constitution principle III).
 *
 * The four-year retention purge still has to delete, but it will not do so
 * through here. It gets its own repository whose only deletion method is a
 * `@Modifying` query bounded by the cut-off date, so the method itself is
 * incapable of removing a record whose retention period is still running.
 * Exposing `delete(entity)` here would make that guarantee a matter of
 * discipline instead of a matter of type.
 */
interface FichajeRepository : Repository<FichajeEntity, UUID> {
    fun save(fichaje: FichajeEntity): FichajeEntity

    fun findById(id: UUID): Optional<FichajeEntity>

    /** Used to reject a second clock-in; the partial unique index is the real guard. */
    fun findByEmpleadoIdAndEstado(empleadoId: UUID, estado: EstadoFichaje): FichajeEntity?

    /**
     * The daily job's query: shifts still open whose entry predates the cut-off.
     * Served by the partial index on `estado = 'EN_CURSO'`.
     */
    fun findAllByEstadoAndEntradaBefore(
        estado: EstadoFichaje,
        corte: Instant
    ): List<FichajeEntity>
}
