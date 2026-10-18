package com.granatum.core.service

import com.granatum.core.domain.model.SheetTask
import com.granatum.core.domain.model.SheetTaskStatus
import com.granatum.core.infrastructure.database.repositories.SheetTaskRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The worker's side of the queue (research.md D-003): each method is one short
 * transaction, and the write to Google happens between them, outside any.
 */
@Service
class SheetTaskLedger(private val tasks: SheetTaskRepository) {

    companion object {
        /** `last_error` is VARCHAR(300). */
        const val MAX_ERROR_LENGTH = 300

        /** `min(2^attempts minutes, 60 minutes)`, with [attempts] already counting the failure. */
        fun backoff(attempts: Int): Duration = Duration.ofMinutes(minOf(1L shl attempts.coerceIn(0, 6), 60L))
    }

    /** Takes up to [limit] due tasks with `FOR UPDATE SKIP LOCKED` and marks them RUNNING. */
    @Transactional
    fun claim(limit: Int, now: Instant): List<SheetTask> =
        tasks.claimDue(now, limit).map {
            it.status = SheetTaskStatus.RUNNING
            it.updatedAt = now
            it.repeatRequested = false
            it.toModel()
        }

    /**
     * DONE - or back to PENDING if a change arrived while it ran, so the sheet
     * ends with the final state. A success supersedes earlier failures of the
     * same target, which are removed from the status.
     */
    @Transactional
    fun succeed(id: UUID, result: SheetTaskResult, now: Instant) {
        val task = tasks.lockById(id)?.takeIf { it.status == SheetTaskStatus.RUNNING } ?: return
        task.rowCount = result.rowCount
        task.lastNamesPending = result.lastNamesPending
        task.lastError = null
        task.updatedAt = now
        if (task.repeatRequested) {
            task.status = SheetTaskStatus.PENDING
            task.attempts = 0
            task.nextAttemptAt = now
            task.repeatRequested = false
        } else {
            task.status = SheetTaskStatus.DONE
            task.completedAt = now
        }
        tasks.deleteFailed(task.kind, task.reference, task.id)
    }

    /**
     * A transient failure waits [backoff] and is retried, up to [maxAttempts];
     * a permanent one is FAILED at once - unless a change arrived while it ran,
     * which deserves its own attempt.
     */
    @Transactional
    fun fail(id: UUID, error: String, permanent: Boolean, maxAttempts: Int, now: Instant) {
        val task = tasks.lockById(id)?.takeIf { it.status == SheetTaskStatus.RUNNING } ?: return
        task.lastError = error.take(MAX_ERROR_LENGTH)
        task.updatedAt = now
        when {
            permanent && task.repeatRequested -> {
                task.status = SheetTaskStatus.PENDING
                task.attempts = 0
                task.nextAttemptAt = now
            }
            permanent -> task.status = SheetTaskStatus.FAILED
            else -> {
                task.attempts += 1
                if (task.attempts >= maxAttempts) {
                    task.status = SheetTaskStatus.FAILED
                } else {
                    task.status = SheetTaskStatus.PENDING
                    task.nextAttemptAt = now.plus(backoff(task.attempts))
                }
            }
        }
        task.repeatRequested = false
    }

    /** RUNNING tasks untouched since [before] - their server died half-way - go back to PENDING. */
    @Transactional
    fun requeueStale(before: Instant, now: Instant): Int {
        val stale = tasks.findStaleRunning(before)
        stale.forEach {
            it.status = SheetTaskStatus.PENDING
            it.nextAttemptAt = now
            it.updatedAt = now
            it.repeatRequested = false
        }
        return stale.size
    }

    @Transactional
    fun purgeDone(before: Instant): Int = tasks.purgeDone(before)
}
