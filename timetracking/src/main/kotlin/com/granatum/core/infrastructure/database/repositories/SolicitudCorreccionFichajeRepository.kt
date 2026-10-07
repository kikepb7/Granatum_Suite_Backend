package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.type.EstadoSolicitud
import com.granatum.core.infrastructure.database.entities.SolicitudCorreccionFichajeEntity
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import org.springframework.data.repository.Repository
import java.util.Optional
import java.util.UUID

/** No delete; see [EmpleadoRepository] for why. */
interface SolicitudCorreccionFichajeRepository :
    Repository<SolicitudCorreccionFichajeEntity, UUID> {

    fun save(solicitud: SolicitudCorreccionFichajeEntity): SolicitudCorreccionFichajeEntity
    fun findById(id: UUID): Optional<SolicitudCorreccionFichajeEntity>
    fun findAllByFichajeIdOrderByCreatedAtDesc(fichajeId: UUID): List<SolicitudCorreccionFichajeEntity>
    fun findAllByEstado(estado: EstadoSolicitud): List<SolicitudCorreccionFichajeEntity>
    fun existsByFichajeIdAndEstado(fichajeId: UUID, estado: EstadoSolicitud): Boolean

    /**
     * Which of [fichajeIds] carry a request in [estado]: ids only, never the
     * requests themselves, served by `idx_solicitudes_fichaje_estado`.
     *
     * Bounded by the caller's own fichajes on purpose. The monthly summary used
     * [findAllByEstado] and so read every approved correction in the history of
     * the company to flag the days of one month (D-011).
     */
    @Query(
        """
        SELECT DISTINCT s.fichaje.id
          FROM SolicitudCorreccionFichajeEntity s
         WHERE s.fichaje.id IN :ids
           AND s.estado = :estado
        """
    )
    fun findFichajeIdsConEstado(
        @Param("ids") fichajeIds: Collection<UUID>,
        @Param("estado") estado: EstadoSolicitud
    ): List<UUID>

    /**
     * The requests in [estado] on [fichajeIds], oldest resolution first, so an
     * export can show each correction in the order it was applied.
     */
    fun findAllByFichajeIdInAndEstadoOrderByResueltaEnAsc(
        fichajeIds: Collection<UUID>,
        estado: EstadoSolicitud
    ): List<SolicitudCorreccionFichajeEntity>

    /**
     * Claims a request for resolution, atomically.
     *
     * Returns the number of rows changed: **1** if this call won it, **0** if it
     * was already resolved. The `WHERE ... AND estado = PENDIENTE` is what makes
     * FR-019 hold under concurrency - a read-then-write cannot, because two
     * simultaneous approvals both read `PENDIENTE` before either writes, and
     * both would then apply their correction to the same fichaje.
     *
     * `flushAutomatically` so the caller's pending entity changes reach the
     * database before this statement evaluates; `clearAutomatically` because a
     * bulk update bypasses the persistence context, and a stale in-memory copy
     * afterwards would be worse than no copy.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE SolicitudCorreccionFichajeEntity s
           SET s.estado = :nuevoEstado,
               s.resueltaPorId = :resolutorId,
               s.resueltaEn = :resueltaEn,
               s.motivoResolucion = :motivoResolucion
         WHERE s.id = :id
           AND s.estado = com.granatum.core.domain.type.EstadoSolicitud.PENDIENTE
        """
    )
    fun resolverSiSiguePendiente(
        @Param("id") id: UUID,
        @Param("nuevoEstado") nuevoEstado: EstadoSolicitud,
        @Param("resolutorId") resolutorId: UUID,
        @Param("resueltaEn") resueltaEn: Instant,
        @Param("motivoResolucion") motivoResolucion: String?
    ): Int
}
