package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.type.ShiftStatus
import com.granatum.core.infrastructure.database.entities.ShiftEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
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
interface ShiftRepository : Repository<ShiftEntity, UUID> {
    fun save(shift: ShiftEntity): ShiftEntity

    fun findById(id: UUID): Optional<ShiftEntity>

    /** Used to reject a second clock-in; the partial unique index is the real guard. */
    fun findByEmployeeIdAndStatus(employeeId: UUID, status: ShiftStatus): ShiftEntity?

    /**
     * The daily job's query: shifts still open whose entry predates the cut-off.
     * Served by the partial index on `status = 'IN_PROGRESS'`.
     */
    fun findAllByStatusAndClockInBefore(
        status: ShiftStatus,
        cutoff: Instant
    ): List<ShiftEntity>

    /**
     * A person's shifts within a range, breaks included.
     *
     * The `@EntityGraph` is what keeps this from being 1 + N queries. Without
     * it, a month of one person's days is ~22 extra selects, and a manager
     * asking for the whole workforce multiplies that by the headcount - which
     * is SC-008 missed by construction rather than by bad luck.
     *
     * **Not paginated, deliberately.** A collection fetch join cannot be
     * paginated in the database: Hibernate would pull every row into memory and
     * paginate there, which is worse than not paginating at all. The date range
     * bounds the result instead. If pagination is ever needed, the fix is to
     * fetch the ids first and load the breaks in a second query - not to add
     * `Pageable` to this method.
     */
    @EntityGraph(attributePaths = ["breaks"])
    fun findAllByEmployeeIdAndClockInBetweenOrderByClockInDesc(
        employeeId: UUID,
        from: Instant,
        to: Instant
    ): List<ShiftEntity>

    /** Same, for every member of staff: the manager's and representative's view. */
    @EntityGraph(attributePaths = ["breaks"])
    fun findAllByClockInBetweenOrderByClockInDesc(
        from: Instant,
        to: Instant
    ): List<ShiftEntity>

    /**
     * One batch of an export: the ids of a person's next [page] shifts with
     * entry in `[start, end)` after the key `(previousClockIn, previousId)`, in
     * `(clockIn, id)` order (D-002).
     *
     * By key and not by `OFFSET`, whose cost grows with every page; the walk is
     * served by `idx_fichajes_empleado_entrada`.
     *
     * Ids only, and the shifts with their breaks in [findAllWithBreaksByIdIn].
     * Fetching the `breaks` collection here with an `@EntityGraph` would make
     * Hibernate drop the `LIMIT` from the SQL and paginate **in memory** after
     * reading every row of the range - the opposite of a batch.
     */
    @Query(
        """
        SELECT f.id FROM ShiftEntity f
         WHERE f.employee.id = :employeeId
           AND f.clockIn >= :start AND f.clockIn < :end
           AND (f.clockIn > :previousClockIn OR (f.clockIn = :previousClockIn AND f.id > :previousId))
         ORDER BY f.clockIn, f.id
        """
    )
    fun findNextBatchIds(
        @Param("employeeId") employeeId: UUID,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
        @Param("previousClockIn") previousClockIn: Instant,
        @Param("previousId") previousId: UUID,
        page: Pageable
    ): List<UUID>

    /**
     * One batch of the Google Sheets copy of a month (feature 012): the ids of
     * the next [page] shifts **of anyone** with entry in `[start, end)` after
     * the key `(previousClockIn, previousId)`, in `(clockIn, id)` order. By key,
     * like [findNextBatchIds], and ids only for the same reason.
     */
    @Query(
        """
        SELECT f.id FROM ShiftEntity f
         WHERE f.clockIn >= :start AND f.clockIn < :end
           AND (f.clockIn > :previousClockIn OR (f.clockIn = :previousClockIn AND f.id > :previousId))
         ORDER BY f.clockIn, f.id
        """
    )
    fun findNextBatchIdsOfAnyone(
        @Param("start") start: Instant,
        @Param("end") end: Instant,
        @Param("previousClockIn") previousClockIn: Instant,
        @Param("previousId") previousId: UUID,
        page: Pageable
    ): List<UUID>

    /** The shifts of one batch, without their breaks. Unordered: the caller keeps the order of the ids. */
    @Query("SELECT f FROM ShiftEntity f WHERE f.id IN :ids")
    fun findAllByIdIn(@Param("ids") ids: Collection<UUID>): List<ShiftEntity>

    /** The shifts of one batch with their breaks, in a single query. Unordered: the caller keeps the order of the ids. */
    @Query("SELECT DISTINCT f FROM ShiftEntity f LEFT JOIN FETCH f.breaks WHERE f.id IN :ids")
    fun findAllWithBreaksByIdIn(@Param("ids") ids: Collection<UUID>): List<ShiftEntity>

    /**
     * Everyone with at least one shift entering in `[start, end)`, active or
     * not: someone who has left still has a register to hand over (FR-015).
     */
    @Query("SELECT DISTINCT f.employee.id FROM ShiftEntity f WHERE f.clockIn >= :start AND f.clockIn < :end")
    fun findEmployeeIdsWithShiftsBetween(
        @Param("start") start: Instant,
        @Param("end") end: Instant
    ): List<UUID>
}
