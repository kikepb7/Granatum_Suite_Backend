package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.model.SheetTaskKind
import com.granatum.core.domain.model.SheetTaskStatus
import com.granatum.core.infrastructure.database.entities.SheetInvoiceRowEntity
import com.granatum.core.infrastructure.database.entities.SheetSettingsEntity
import com.granatum.core.infrastructure.database.entities.SheetTaskEntity
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.Optional
import java.util.UUID

interface SheetSettingsRepository : Repository<SheetSettingsEntity, Short> {
    fun save(settings: SheetSettingsEntity): SheetSettingsEntity
    fun findById(id: Short): Optional<SheetSettingsEntity>
}

interface SheetInvoiceRowRepository : Repository<SheetInvoiceRowEntity, UUID> {
    fun save(row: SheetInvoiceRowEntity): SheetInvoiceRowEntity
    fun findById(invoiceId: UUID): Optional<SheetInvoiceRowEntity>
    fun findAllBySpreadsheetIdAndTab(spreadsheetId: String, tab: String): List<SheetInvoiceRowEntity>
    fun deleteById(invoiceId: UUID)
}

/** The coalescing insert, which needs `ON CONFLICT … RETURNING` and so plain JDBC. */
interface SheetTaskRepositoryCustom {
    /**
     * Queues a write for `(kind, reference)`, or joins the live one (research.md
     * D-003): a `PENDING` task restarts its attempts and becomes due now; a
     * `RUNNING` one is marked to run again when it ends. A `DONE` or `FAILED`
     * task never blocks a new one. Returns the id of the live task. Joins the
     * caller's transaction.
     */
    fun upsertPending(kind: SheetTaskKind, reference: String, requestedBy: UUID?, now: Instant): UUID
}

interface SheetTaskRepository : Repository<SheetTaskEntity, UUID>, SheetTaskRepositoryCustom {
    fun save(task: SheetTaskEntity): SheetTaskEntity
    fun findById(id: UUID): Optional<SheetTaskEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM SheetTaskEntity t WHERE t.id = :id")
    fun lockById(@Param("id") id: UUID): SheetTaskEntity?

    /**
     * The due tasks, locked, skipping those another worker holds: two workers
     * (two instances, or an immediate pass next to the scheduled one) never
     * take the same task. Call inside a short transaction.
     */
    @Query(
        value = """
            SELECT * FROM sheet_tasks
             WHERE status = 'PENDING' AND next_attempt_at <= :now
             ORDER BY next_attempt_at
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun claimDue(@Param("now") now: Instant, @Param("limit") limit: Int): List<SheetTaskEntity>

    /** RUNNING tasks untouched since [before]: their server died half-way. Locked, skipping held rows. */
    @Query(
        value = """
            SELECT * FROM sheet_tasks
             WHERE status = 'RUNNING' AND updated_at < :before
               FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun findStaleRunning(@Param("before") before: Instant): List<SheetTaskEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM SheetTaskEntity t WHERE t.status = :status ORDER BY t.updatedAt")
    fun lockAllByStatus(@Param("status") status: SheetTaskStatus): List<SheetTaskEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT t FROM SheetTaskEntity t WHERE t.kind = :kind AND t.reference = :reference " +
            "AND t.status IN (com.granatum.core.domain.model.SheetTaskStatus.PENDING, com.granatum.core.domain.model.SheetTaskStatus.RUNNING)"
    )
    fun lockLive(@Param("kind") kind: SheetTaskKind, @Param("reference") reference: String): SheetTaskEntity?

    fun countByStatusIn(statuses: Collection<SheetTaskStatus>): Long

    @Query("SELECT max(t.completedAt) FROM SheetTaskEntity t WHERE t.status = com.granatum.core.domain.model.SheetTaskStatus.DONE")
    fun lastSuccessAt(): Instant?

    /** Not DONE, or finished since [since]; newest first. */
    @Query(
        "SELECT t FROM SheetTaskEntity t " +
            "WHERE t.status <> com.granatum.core.domain.model.SheetTaskStatus.DONE OR t.completedAt >= :since " +
            "ORDER BY t.updatedAt DESC, t.createdAt DESC"
    )
    fun findRecent(@Param("since") since: Instant, limit: Limit): List<SheetTaskEntity>

    /** Earlier failures of a target that has just been written: the success supersedes them. */
    @Modifying
    @Query(
        "DELETE FROM SheetTaskEntity t WHERE t.kind = :kind AND t.reference = :reference " +
            "AND t.status = com.granatum.core.domain.model.SheetTaskStatus.FAILED AND t.id <> :except"
    )
    fun deleteFailed(@Param("kind") kind: SheetTaskKind, @Param("reference") reference: String, @Param("except") except: UUID): Int

    @Modifying
    @Query("DELETE FROM SheetTaskEntity t WHERE t.id = :id")
    fun deleteTask(@Param("id") id: UUID): Int

    /** DONE tasks are operational, not a legal record: kept 90 days (data-model.md). */
    @Modifying
    @Query(
        "DELETE FROM SheetTaskEntity t WHERE t.status = com.granatum.core.domain.model.SheetTaskStatus.DONE " +
            "AND t.completedAt < :before"
    )
    fun purgeDone(@Param("before") before: Instant): Int
}

/** Spring Data picks this up by name as the implementation of [SheetTaskRepositoryCustom]. */
class SheetTaskRepositoryCustomImpl(private val jdbc: JdbcTemplate) : SheetTaskRepositoryCustom {

    override fun upsertPending(kind: SheetTaskKind, reference: String, requestedBy: UUID?, now: Instant): UUID {
        val at = Timestamp.from(now)
        return jdbc.queryForObject(
            """
            INSERT INTO sheet_tasks
                (id, kind, reference, status, attempts, next_attempt_at, requested_by,
                 repeat_requested, created_at, updated_at)
            VALUES (?, ?, ?, 'PENDING', 0, ?, ?, false, ?, ?)
            ON CONFLICT (kind, reference) WHERE status IN ('PENDING', 'RUNNING')
            DO UPDATE SET
                attempts         = CASE WHEN sheet_tasks.status = 'PENDING' THEN 0 ELSE sheet_tasks.attempts END,
                next_attempt_at  = CASE WHEN sheet_tasks.status = 'PENDING' THEN EXCLUDED.next_attempt_at
                                        ELSE sheet_tasks.next_attempt_at END,
                repeat_requested = sheet_tasks.repeat_requested OR sheet_tasks.status = 'RUNNING',
                requested_by     = COALESCE(EXCLUDED.requested_by, sheet_tasks.requested_by),
                -- A RUNNING task's updated_at says when it was claimed; touching
                -- it would hide a stale one from the recovery.
                updated_at       = CASE WHEN sheet_tasks.status = 'PENDING' THEN EXCLUDED.updated_at
                                        ELSE sheet_tasks.updated_at END
            RETURNING id
            """.trimIndent(),
            UUID::class.java,
            UUID.randomUUID(), kind.name, reference, at, requestedBy, at, at
        )!!
    }
}
