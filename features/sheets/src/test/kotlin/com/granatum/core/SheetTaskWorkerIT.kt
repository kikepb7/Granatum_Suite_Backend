package com.granatum.core

import com.granatum.core.domain.model.SheetTask
import com.granatum.core.domain.model.SheetTaskKind
import com.granatum.core.domain.port.SheetsFailureReason
import com.granatum.core.domain.port.SheetsPermanentException
import com.granatum.core.domain.port.SheetsTransientException
import com.granatum.core.infrastructure.database.repositories.SheetTaskRepository
import com.granatum.core.scheduling.SheetTaskWorker
import com.granatum.core.domain.port.SheetsUnavailableException
import com.granatum.core.service.SheetTaskAbortedException
import com.granatum.core.service.SheetTaskHandler
import com.granatum.core.service.SheetTaskLedger
import com.granatum.core.service.SheetTaskQueue
import com.granatum.core.service.SheetTaskResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The worker against a real Postgres and the [FakeSheetsClient] (research.md
 * D-003): results, retries with growing waits, permanent failures, repeats,
 * recovery of abandoned tasks, `SKIP LOCKED`, purge, and no transaction open
 * while Google is called.
 *
 * Each test builds its own [SheetTaskWorker] with a scripted handler, instead of
 * registering a handler bean: the real handlers of US2 and US3 will be beans of
 * this same context, and two handlers for one kind fail the startup.
 */
@SpringBootTest(classes = [SheetsTestApplication::class])
class SheetTaskWorkerIT : SheetsDatabase() {

    @Autowired lateinit var queue: SheetTaskQueue
    @Autowired lateinit var ledger: SheetTaskLedger
    @Autowired lateinit var tasks: SheetTaskRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var transactions: TransactionTemplate

    private val client = FakeSheetsClient()

    /** Writes the month's tab through the fake, like the real shift writer will. */
    inner class ScriptedHandler(override val kind: SheetTaskKind = SheetTaskKind.SHIFT_MONTH) : SheetTaskHandler {
        val handled = CopyOnWriteArrayList<UUID>()
        @Volatile var before: (SheetTask) -> Unit = {}

        override fun handle(task: SheetTask): SheetTaskResult {
            handled += task.id
            before(task)
            client.rewriteTab("hoja-de-fichajes", task.reference, listOf("Nombre"), emptyList())
            return SheetTaskResult(rowCount = 3, lastNamesPending = 1)
        }
    }

    private fun worker(vararg handlers: SheetTaskHandler, immediate: Boolean = false) =
        SheetTaskWorker(handlers.toList(), ledger, maxAttempts = 8, immediate = immediate, batchSize = 2)

    @BeforeEach
    fun clean() {
        jdbc.update("DELETE FROM sheet_tasks")
        client.reset()
    }

    private fun row(id: UUID): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM sheet_tasks WHERE id = ?", id)

    private fun instant(value: Any?): Instant = (value as java.sql.Timestamp).toInstant()

    @Test
    fun `a due task runs and ends DONE with its result`() {
        val handler = ScriptedHandler()
        val id = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", UUID.randomUUID())

        assertEquals(1, worker(handler).runOnce())

        val task = row(id)
        assertEquals("DONE", task["status"])
        assertNotNull(task["completed_at"])
        assertEquals(3, task["row_count"])
        assertEquals(1, task["last_names_pending"])
        assertNull(task["last_error"])
        assertEquals(listOf("2026-10"), client.calls.map { it.tab })
    }

    @Test
    fun `Google is called with no transaction open`() {
        queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null)
        worker(ScriptedHandler()).runOnce()
        assertFalse(client.calls.single().inTransaction, "a slow call to Google must not hold a database connection")
    }

    @Test
    fun `a transient error waits min(2^attempts, 60) minutes and counts the attempt`() {
        val id = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null)
        client.nextFailures += SheetsTransientException("Google no ha podido atender la petición (HTTP 503)")

        val before = Instant.now()
        worker(ScriptedHandler()).runOnce()

        val task = row(id)
        assertEquals("PENDING", task["status"])
        assertEquals(1, task["attempts"])
        assertEquals("Google no ha podido atender la petición (HTTP 503)", task["last_error"])
        assertEquals("TRANSIENT", task["error_reason"], "a pending task shows why it waits")
        val wait = Duration.between(before, instant(task["next_attempt_at"]))
        assertTrue(wait > Duration.ofSeconds(110) && wait < Duration.ofSeconds(130), "2^1 minutes, was $wait")

        assertEquals(Duration.ofMinutes(4), SheetTaskLedger.backoff(2))
        assertEquals(Duration.ofMinutes(32), SheetTaskLedger.backoff(5))
        assertEquals(Duration.ofMinutes(60), SheetTaskLedger.backoff(6))
        assertEquals(Duration.ofMinutes(60), SheetTaskLedger.backoff(7))
    }

    @Test
    fun `after max-attempts transient errors the task is FAILED`() {
        val id = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null)
        jdbc.update("UPDATE sheet_tasks SET attempts = 7 WHERE id = ?", id)
        client.nextFailures += SheetsTransientException("Google ha limitado temporalmente las peticiones (cuota)")

        worker(ScriptedHandler()).runOnce()

        val task = row(id)
        assertEquals("FAILED", task["status"])
        assertEquals(8, task["attempts"])
        assertEquals("Google ha limitado temporalmente las peticiones (cuota)", task["last_error"])
        assertEquals("TRANSIENT", task["error_reason"])
    }

    @Test
    fun `a permanent error is FAILED at once with a readable reason`() {
        val id = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null)
        client.nextFailures += SheetsPermanentException(SheetsFailureReason.NOT_SHARED)

        worker(ScriptedHandler()).runOnce()

        val task = row(id)
        assertEquals("FAILED", task["status"])
        assertEquals(0, task["attempts"], "not retried")
        assertEquals("La hoja no está compartida con la cuenta técnica de Google", task["last_error"])
        assertEquals("NOT_SHARED", task["error_reason"])
    }

    @Test
    fun `an unexpected error counts as transient and says nothing of the data`() {
        val id = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null)
        val handler = ScriptedHandler().apply { before = { error("DNI 12345678Z en el mensaje") } }

        worker(handler).runOnce()

        val task = row(id)
        assertEquals("PENDING", task["status"])
        assertEquals(1, task["attempts"])
        assertFalse((task["last_error"] as String).contains("12345678Z"))
        assertEquals("TRANSIENT", task["error_reason"])
    }

    @Test
    fun `a kind with no handler is FAILED with a clear reason`() {
        val id = queue.enqueue(SheetTaskKind.INVOICE, UUID.randomUUID().toString(), null)

        worker(ScriptedHandler(SheetTaskKind.SHIFT_MONTH)).runOnce()

        val task = row(id)
        assertEquals("FAILED", task["status"])
        assertEquals("No hay ningún escritor para las tareas de tipo INVOICE", task["last_error"])
        assertEquals("NO_HANDLER", task["error_reason"])
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `every other permanent failure has its structured reason`() {
        val cases = listOf(
            SheetsPermanentException(SheetsFailureReason.NOT_FOUND) to "NOT_FOUND",
            SheetsPermanentException(SheetsFailureReason.READ_ONLY) to "READ_ONLY",
            SheetsPermanentException(SheetsFailureReason.BAD_REQUEST) to "BAD_REQUEST",
            SheetsUnavailableException() to "UNAVAILABLE",
            SheetTaskAbortedException("La hoja de fichajes no está configurada") to "ABORTED"
        )
        cases.forEachIndexed { i, (failure, reason) ->
            val id = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-0${i + 1}", null)
            client.nextFailures += failure
            worker(ScriptedHandler()).runOnce()
            assertEquals("FAILED", row(id)["status"], reason)
            assertEquals(reason, row(id)["error_reason"])
        }
    }

    @Test
    fun `a success and a retry clear the reason`() {
        val id = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null)
        client.nextFailures += SheetsTransientException("Google no responde")
        worker(ScriptedHandler()).runOnce()
        assertEquals("TRANSIENT", row(id)["error_reason"])

        jdbc.update("UPDATE sheet_tasks SET next_attempt_at = now() WHERE id = ?", id)
        worker(ScriptedHandler()).runOnce()
        assertEquals("DONE", row(id)["status"])
        assertNull(row(id)["error_reason"])

        val failed = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-11", null)
        client.nextFailures += SheetsPermanentException(SheetsFailureReason.NOT_SHARED)
        worker(ScriptedHandler()).runOnce()
        assertEquals("NOT_SHARED", row(failed)["error_reason"])
        assertNull(queue.retry(failed).errorReason)
        assertNull(row(failed)["error_reason"])
    }

    @Test
    fun `a change while running makes the task run again`() {
        val handler = ScriptedHandler()
        val first = AtomicInteger()
        handler.before = { if (first.getAndIncrement() == 0) queue.enqueue(it.kind, it.reference, null) }
        val id = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null)

        worker(handler).runOnce()

        assertEquals(listOf(id, id), handler.handled, "the second run writes the final state")
        assertEquals("DONE", row(id)["status"])
        assertEquals(false, row(id)["repeat_requested"])
    }

    @Test
    fun `a success removes earlier failures of the same target`() {
        val failed = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null)
        jdbc.update("UPDATE sheet_tasks SET status = 'FAILED' WHERE id = ?", failed)
        val id = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null)

        worker(ScriptedHandler()).runOnce()

        assertEquals(listOf(id), jdbc.queryForList("SELECT id FROM sheet_tasks", UUID::class.java))
    }

    @Test
    fun `a task RUNNING for more than 10 minutes goes back to PENDING and runs`() {
        val stale = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-09", null)
        val recent = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null)
        jdbc.update("UPDATE sheet_tasks SET status = 'RUNNING', updated_at = now() - interval '11 minutes' WHERE id = ?", stale)
        jdbc.update("UPDATE sheet_tasks SET status = 'RUNNING', updated_at = now() - interval '5 minutes' WHERE id = ?", recent)
        val handler = ScriptedHandler()

        worker(handler).runOnce()

        assertEquals(listOf(stale), handler.handled)
        assertEquals("DONE", row(stale)["status"])
        assertEquals("RUNNING", row(recent)["status"], "may still be running on another server")
    }

    @Test
    fun `a claim skips the tasks another worker holds`() {
        repeat(2) { queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-0${it + 1}", null) }
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val held = pool.submit<UUID> {
                transactions.execute {
                    val locked = tasks.claimDue(Instant.now(), 1).single().id
                    holding.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    locked
                }
            }
            assertTrue(holding.await(10, TimeUnit.SECONDS))

            val claimed = Executors.newSingleThreadExecutor().submit<List<SheetTask>> { ledger.claim(10, Instant.now()) }
                .get(5, TimeUnit.SECONDS) // would block on the held row without SKIP LOCKED

            release.countDown()
            assertEquals(1, claimed.size)
            assertTrue(claimed.single().id != held.get(10, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdown()
        }
    }

    @Test
    fun `two workers in parallel never run the same task`() {
        val ids = (1..6).map { queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-0$it", null) }
        val handler = ScriptedHandler().apply { before = { Thread.sleep(100) } }
        val a = worker(handler)
        val b = worker(handler)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val runs = listOf(a, b).map { w -> pool.submit<Int> { start.await(); w.runOnce() } }
            start.countDown()
            assertEquals(6, runs.sumOf { it.get(30, TimeUnit.SECONDS) })
        } finally {
            pool.shutdown()
        }

        assertEquals(ids.sorted(), handler.handled.sorted(), "each task exactly once")
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM sheet_tasks WHERE status = 'DONE'", Int::class.java))
    }

    @Test
    fun `DONE tasks older than 90 days are purged`() {
        val old = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-01", null)
        val kept = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-02", null)
        val failed = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-03", null)
        jdbc.update("UPDATE sheet_tasks SET status = 'DONE', completed_at = now() - interval '91 days' WHERE id = ?", old)
        jdbc.update("UPDATE sheet_tasks SET status = 'DONE', completed_at = now() - interval '89 days' WHERE id = ?", kept)
        jdbc.update("UPDATE sheet_tasks SET status = 'FAILED', updated_at = now() - interval '200 days' WHERE id = ?", failed)

        worker(ScriptedHandler()).runOnce()

        assertEquals(
            setOf(kept, failed),
            jdbc.queryForList("SELECT id FROM sheet_tasks", UUID::class.java).toSet(),
            "only DONE tasks are purged: a failure waits for the ADMIN"
        )
    }

    @Test
    fun `kick runs a pass in the background`() {
        val id = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null)
        val worker = worker(ScriptedHandler(), immediate = true)
        try {
            worker.kick()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (row(id)["status"] != "DONE" && System.nanoTime() < deadline) Thread.sleep(50)
            assertEquals("DONE", row(id)["status"])
            assertFalse(client.calls.single().inTransaction)
        } finally {
            worker.stop()
        }
    }

    @Test
    fun `the clock of the queue and the worker agree on when a task is due`() {
        val id = queue.enqueue(SheetTaskKind.SHIFT_MONTH, "2026-10", null)
        jdbc.update("UPDATE sheet_tasks SET next_attempt_at = ? WHERE id = ?", OffsetDateTime.now().plusMinutes(5), id)
        val handler = ScriptedHandler()

        assertEquals(0, worker(handler).runOnce(), "not due yet")
        assertTrue(handler.handled.isEmpty())
    }
}
