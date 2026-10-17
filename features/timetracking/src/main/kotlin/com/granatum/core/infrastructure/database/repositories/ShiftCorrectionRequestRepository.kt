package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.type.CorrectionStatus
import com.granatum.core.infrastructure.database.entities.ShiftCorrectionRequestEntity
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import org.springframework.data.repository.Repository
import java.util.Optional
import java.util.UUID

/** No delete; see [EmployeeRepository] for why. */
interface ShiftCorrectionRequestRepository :
    Repository<ShiftCorrectionRequestEntity, UUID> {

    fun save(correctionRequest: ShiftCorrectionRequestEntity): ShiftCorrectionRequestEntity
    fun findById(id: UUID): Optional<ShiftCorrectionRequestEntity>
    fun findAllByShiftIdOrderByCreatedAtDesc(shiftId: UUID): List<ShiftCorrectionRequestEntity>
    fun findAllByStatus(status: CorrectionStatus): List<ShiftCorrectionRequestEntity>
    fun existsByShiftIdAndStatus(shiftId: UUID, status: CorrectionStatus): Boolean

    /**
     * Which of [shiftIds] carry a request in [status]: ids only, never the
     * requests themselves, served by `idx_solicitudes_fichaje_estado`.
     *
     * Bounded by the caller's own fichajes on purpose. The monthly summary used
     * [findAllByStatus] and so read every approved correction in the history of
     * the company to flag the days of one month (D-011).
     */
    @Query(
        """
        SELECT DISTINCT s.shift.id
          FROM ShiftCorrectionRequestEntity s
         WHERE s.shift.id IN :ids
           AND s.status = :status
        """
    )
    fun findShiftIdsWithStatus(
        @Param("ids") shiftIds: Collection<UUID>,
        @Param("status") status: CorrectionStatus
    ): List<UUID>

    /**
     * The requests in [status] on [shiftIds], oldest resolution first, so an
     * export can show each correction in the order it was applied.
     */
    fun findAllByShiftIdInAndStatusOrderByResolvedAtAsc(
        shiftIds: Collection<UUID>,
        status: CorrectionStatus
    ): List<ShiftCorrectionRequestEntity>

    /**
     * Claims a request for resolution, atomically.
     *
     * Returns the number of rows changed: **1** if this call won it, **0** if it
     * was already resolved. The `WHERE ... AND status = PENDING` is what makes
     * FR-019 hold under concurrency - a read-then-write cannot, because two
     * simultaneous approvals both read `PENDING` before either writes, and
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
        UPDATE ShiftCorrectionRequestEntity s
           SET s.status = :newStatus,
               s.resolvedById = :resolverId,
               s.resolvedAt = :resolvedAt,
               s.resolutionReason = :resolutionReason
         WHERE s.id = :id
           AND s.status = com.granatum.core.domain.type.CorrectionStatus.PENDING
        """
    )
    fun resolveIfStillPending(
        @Param("id") id: UUID,
        @Param("newStatus") newStatus: CorrectionStatus,
        @Param("resolverId") resolverId: UUID,
        @Param("resolvedAt") resolvedAt: Instant,
        @Param("resolutionReason") resolutionReason: String?
    ): Int
}
