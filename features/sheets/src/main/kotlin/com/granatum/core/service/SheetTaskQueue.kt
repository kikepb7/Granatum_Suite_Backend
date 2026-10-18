package com.granatum.core.service

import com.granatum.core.domain.exception.SheetTaskNotFailedException
import com.granatum.core.domain.exception.SheetTaskNotFoundException
import com.granatum.core.domain.model.SheetQueueStatus
import com.granatum.core.domain.model.SheetTask
import com.granatum.core.domain.model.SheetTaskKind
import com.granatum.core.domain.model.SheetTaskStatus
import com.granatum.core.infrastructure.database.entities.SheetTaskEntity
import com.granatum.core.infrastructure.database.repositories.SheetTaskRepository
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The queue of writes to Google, seen from the rest of the module (research.md
 * D-003): queue, retry, read. The worker's side - claiming and recording
 * results - is [SheetTaskLedger].
 *
 * Nothing here calls Google; every method is a short transaction, or joins the
 * caller's one.
 */
@Service
class SheetTaskQueue(
    private val tasks: SheetTaskRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    companion object {
        const val RECENT_LIMIT = 20
        val RECENT_WINDOW: Duration = Duration.ofHours(24)
    }

    /**
     * Queues a write, or joins the live task of the same `(kind, reference)`.
     * Joins the caller's transaction, so a task queued from a
     * `BEFORE_COMMIT` listener commits or rolls back with the change that
     * caused it. Returns the id of the live task.
     *
     * The caller wanting an immediate pass calls
     * [com.granatum.core.scheduling.SheetTaskWorker.kick] **after** its
     * transaction commits, or the worker will not see the task yet.
     */
    @Transactional
    fun enqueue(kind: SheetTaskKind, reference: String, requestedBy: UUID?): UUID =
        tasks.upsertPending(kind, reference, requestedBy, clock.instant())

    /**
     * Puts a FAILED task back in the queue with its attempts at zero. If
     * another live task of the same target was queued since, that one is due
     * now instead and the failure, superseded, is removed.
     */
    @Transactional
    fun retry(id: UUID): SheetTask {
        val task = tasks.lockById(id) ?: throw SheetTaskNotFoundException(id)
        if (task.status != SheetTaskStatus.FAILED) throw SheetTaskNotFailedException()
        return requeue(task)
    }

    /** [retry] for every FAILED task. Returns how many were put back. */
    @Transactional
    fun retryAllFailed(): Int {
        val failed = tasks.lockAllByStatus(SheetTaskStatus.FAILED)
        failed.forEach { requeue(it) }
        return failed.size
    }

    @Transactional(readOnly = true)
    fun find(id: UUID): SheetTask = tasks.findById(id).map { it.toModel() }.orElseThrow { SheetTaskNotFoundException(id) }

    @Transactional(readOnly = true)
    fun status(): SheetQueueStatus = SheetQueueStatus(
        lastSuccessAt = tasks.lastSuccessAt(),
        pending = tasks.countByStatusIn(listOf(SheetTaskStatus.PENDING, SheetTaskStatus.RUNNING)),
        failed = tasks.countByStatusIn(listOf(SheetTaskStatus.FAILED)),
        recent = tasks.findRecent(clock.instant().minus(RECENT_WINDOW), Limit.of(RECENT_LIMIT)).map { it.toModel() }
    )

    private fun requeue(task: SheetTaskEntity): SheetTask {
        val now = clock.instant()
        val live = tasks.lockLive(task.kind, task.reference)
        if (live != null) {
            // The same as the coalescing upsert would do to it.
            if (live.status == SheetTaskStatus.PENDING) {
                live.attempts = 0
                live.nextAttemptAt = now
                live.updatedAt = now
            } else {
                live.repeatRequested = true
            }
            tasks.deleteTask(task.id)
            return live.toModel()
        }
        task.status = SheetTaskStatus.PENDING
        task.attempts = 0
        task.nextAttemptAt = now
        task.lastError = null
        task.completedAt = null
        task.repeatRequested = false
        task.updatedAt = now
        return task.toModel()
    }
}

internal fun SheetTaskEntity.toModel() = SheetTask(
    id = id,
    kind = kind,
    reference = reference,
    status = status,
    attempts = attempts,
    nextAttemptAt = nextAttemptAt,
    lastError = lastError,
    requestedBy = requestedBy,
    rowCount = rowCount,
    lastNamesPending = lastNamesPending,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt
)
