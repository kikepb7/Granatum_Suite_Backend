package com.granatum.core

import com.granatum.core.domain.exception.SheetTaskNotFailedException
import com.granatum.core.domain.exception.SheetTaskNotFoundException
import com.granatum.core.domain.model.SheetTaskKind
import com.granatum.core.domain.model.SheetTaskStatus
import com.granatum.core.service.SheetTaskQueue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Coalescing and retrying in the queue of writes (research.md D-003). */
@SpringBootTest(classes = [SheetsTestApplication::class])
class SheetTaskQueueIT : SheetsDatabase() {

    @Autowired lateinit var queue: SheetTaskQueue
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var transactions: TransactionTemplate

    @BeforeEach
    fun clean() {
        jdbc.update("DELETE FROM sheet_tasks")
    }

    private fun count(where: String = "true"): Int =
        jdbc.queryForObject("SELECT count(*) FROM sheet_tasks WHERE $where", Int::class.java)!!

    private fun column(id: UUID, name: String): Any? =
        jdbc.queryForObject("SELECT $name FROM sheet_tasks WHERE id = ?", Any::class.java, id)

    private fun setStatus(id: UUID, status: SheetTaskStatus) =
        jdbc.update("UPDATE sheet_tasks SET status = ? WHERE id = ?", status.name, id)

    @Test
    fun `queuing the same target twice leaves one pending task`() {
        val first = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", UUID.randomUUID())
        val second = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", UUID.randomUUID())

        assertEquals(first, second)
        assertEquals(1, count())
        assertEquals("PENDING", column(first, "status"))
        assertEquals(1, count("kind = 'SHIFT_MONTH'"))
    }

    @Test
    fun `joining a pending task restarts its attempts and makes it due now`() {
        val id = queue.enqueue(SheetTaskKind.INVOICE, UUID.randomUUID().toString(), null)
        jdbc.update(
            "UPDATE sheet_tasks SET attempts = 3, next_attempt_at = now() + interval '1 hour' WHERE id = ?", id
        )

        queue.enqueue(SheetTaskKind.INVOICE, jdbc.queryForObject("SELECT reference FROM sheet_tasks", String::class.java)!!, null)

        assertEquals(0, column(id, "attempts"))
        // A margin for the clock of the Docker VM, which can lag the JVM's by
        // some milliseconds; the point is "now" against the hour it had left.
        assertTrue(count("next_attempt_at <= now() + interval '5 seconds'") == 1)
    }

    @Test
    fun `a change for a running task marks it to run again`() {
        val id = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null)
        setStatus(id, SheetTaskStatus.RUNNING)
        val claimedAt = jdbc.queryForObject("SELECT updated_at FROM sheet_tasks WHERE id = ?", java.time.OffsetDateTime::class.java, id)

        assertEquals(id, queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null))

        assertEquals(1, count())
        assertEquals(true, column(id, "repeat_requested"))
        assertEquals("RUNNING", column(id, "status"))
        assertEquals(
            claimedAt,
            jdbc.queryForObject("SELECT updated_at FROM sheet_tasks WHERE id = ?", java.time.OffsetDateTime::class.java, id),
            "updated_at still says when it was claimed, for the stale recovery"
        )
    }

    @Test
    fun `a failed task does not block a new one`() {
        val failed = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null)
        setStatus(failed, SheetTaskStatus.FAILED)

        val fresh = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null)

        assertNotEquals(failed, fresh)
        assertEquals(2, count())
        assertEquals("PENDING", column(fresh, "status"))
    }

    @Test
    fun `the task joins the caller's transaction and rolls back with it`() {
        runCatching {
            transactions.executeWithoutResult {
                queue.enqueue(SheetTaskKind.INVOICE, UUID.randomUUID().toString(), null)
                error("rollback")
            }
        }
        assertEquals(0, count())
    }

    @Test
    fun `retrying a failed task puts it back with zero attempts`() {
        val id = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-09", null)
        jdbc.update("UPDATE sheet_tasks SET status = 'FAILED', attempts = 8, last_error = 'x' WHERE id = ?", id)

        val retried = queue.retry(id)

        assertEquals(id, retried.id)
        assertEquals(SheetTaskStatus.PENDING, retried.status)
        assertEquals(0, retried.attempts)
        assertEquals("PENDING", column(id, "status"))
    }

    @Test
    fun `retrying a failure whose target already has a live task keeps the live one`() {
        val failed = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-09", null)
        setStatus(failed, SheetTaskStatus.FAILED)
        val live = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-09", null)
        jdbc.update(
            "UPDATE sheet_tasks SET attempts = 2, next_attempt_at = ? WHERE id = ?",
            java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)), live
        )

        val retried = queue.retry(failed)

        assertEquals(live, retried.id)
        assertEquals(1, count(), "the superseded failure is gone")
        assertEquals(0, column(live, "attempts"))
    }

    @Test
    fun `only failed tasks can be retried, and a missing one is not found`() {
        val id = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-08", null)
        assertFailsWith<SheetTaskNotFailedException> { queue.retry(id) }
        assertFailsWith<SheetTaskNotFoundException> { queue.retry(UUID.randomUUID()) }
    }

    @Test
    fun `retrying every failure counts them`() {
        repeat(3) {
            setStatus(queue.enqueue(SheetTaskKind.INVOICE, UUID.randomUUID().toString(), null), SheetTaskStatus.FAILED)
        }
        queue.enqueue(SheetTaskKind.INVOICE, UUID.randomUUID().toString(), null)

        assertEquals(3, queue.retryAllFailed())
        assertEquals(4, count("status = 'PENDING'"))
    }
}
