package com.granatum.core

import com.granatum.core.domain.model.SheetTaskKind
import com.granatum.core.service.SheetTaskQueue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * US4: the state of the queue and the retries (contracts/README.md, FR-023).
 * `sheets.worker.immediate` is off in the test configuration, so no background
 * pass changes the tasks between a request and its assertions.
 */
@SpringBootTest(classes = [SheetsTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WithFakeSheetsClient::class, SheetsPermissiveSecurityTestConfig::class)
class SheetsStatusControllerIT : SheetsHttp() {

    @Autowired lateinit var queue: SheetTaskQueue
    @Autowired lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clean() {
        jdbc.update("DELETE FROM sheet_tasks")
    }

    /** A task in any state, `minutesAgo` since its last change. */
    private fun task(
        status: String,
        minutesAgo: Long,
        kind: SheetTaskKind = SheetTaskKind.INVOICE,
        reference: String = UUID.randomUUID().toString(),
        lastError: String? = null
    ): UUID {
        val id = queue.enqueue(kind, reference, null)
        val at = java.sql.Timestamp.from(Instant.now().minusSeconds(minutesAgo * 60))
        jdbc.update(
            "UPDATE sheet_tasks SET status = ?, updated_at = ?, last_error = ?, " +
                "completed_at = CASE WHEN ? = 'DONE' THEN ?::timestamptz END WHERE id = ?",
            status, at, lastError, status, at, id
        )
        return id
    }

    @Test
    fun `the status shows the last success, the pending and failed counts and the recent tasks newest first`() {
        val oldDone = task("DONE", 60 * 30)          // 30 hours ago: out of `recent`
        val done = task("DONE", 10)
        val failed = task("FAILED", 5, lastError = "La hoja no está compartida con la cuenta técnica de Google")
        val running = task("RUNNING", 2)
        val pending = task("PENDING", 1)

        val r = send(HttpMethod.GET, "/api/sheets/status")

        assertEquals(200, r.status, r.raw)
        assertEquals(2, r.body["pending"].asInt(), "PENDING and RUNNING are both still to be written")
        assertEquals(1, r.body["failed"].asInt())
        val lastSuccess = Instant.parse(r.body["lastSuccessAt"].asText())
        assertTrue(lastSuccess.isAfter(Instant.now().minusSeconds(11 * 60)), "the latest DONE, not the oldest")
        val recent = r.body["recent"].map { it["id"].asText() }
        assertEquals(listOf(pending, running, failed, done).map { it.toString() }, recent)
        assertTrue(oldDone.toString() !in recent)
        assertEquals(
            "La hoja no está compartida con la cuenta técnica de Google",
            r.body["recent"][2]["lastError"].asText()
        )
    }

    @Test
    fun `recent holds at most 20 tasks`() {
        repeat(25) { task("FAILED", it.toLong()) }
        val r = send(HttpMethod.GET, "/api/sheets/status")
        assertEquals(20, r.body["recent"].size())
        assertEquals(25, r.body["failed"].asInt())
    }

    @Test
    fun `an empty queue has no last success`() {
        val r = send(HttpMethod.GET, "/api/sheets/status")
        assertTrue(r.body["lastSuccessAt"].isNull)
        assertEquals(0, r.body["pending"].asInt())
        assertEquals(0, r.body["recent"].size())
    }

    @Test
    fun `a task is shown by id, and a missing one is not found`() {
        val id = task("DONE", 1, SheetTaskKind.SHIFT_MONTH, "2026-10")
        jdbc.update("UPDATE sheet_tasks SET row_count = 92, last_names_pending = 1, attempts = 1 WHERE id = ?", id)

        val r = send(HttpMethod.GET, "/api/sheets/tasks/$id")
        assertEquals(200, r.status)
        assertEquals("SHIFT_MONTH", r.body["kind"].asText())
        assertEquals("2026-10", r.body["reference"].asText())
        assertEquals("DONE", r.body["status"].asText())
        assertEquals(1, r.body["attempts"].asInt())
        assertEquals(92, r.body["rowCount"].asInt())
        assertEquals(1, r.body["lastNamesPending"].asInt())
        assertTrue(r.body["lastError"].isNull)
        listOf("createdAt", "updatedAt", "completedAt").forEach { assertTrue(r.body[it].isTextual, it) }

        val missing = send(HttpMethod.GET, "/api/sheets/tasks/${UUID.randomUUID()}")
        assertEquals(404, missing.status)
        assertEquals("TASK_NOT_FOUND", missing.body["code"].asText())

        assertEquals(400, send(HttpMethod.GET, "/api/sheets/tasks/no-es-un-uuid").status)
    }

    @Test
    fun `a failed task can be retried, and any other cannot`() {
        val failed = task("FAILED", 1, lastError = "x")
        jdbc.update("UPDATE sheet_tasks SET attempts = 8 WHERE id = ?", failed)

        val r = send(HttpMethod.POST, "/api/sheets/tasks/$failed/retry")
        assertEquals(202, r.status, r.raw)
        assertEquals("PENDING", r.body["status"].asText())
        assertEquals(0, r.body["attempts"].asInt())
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM sheet_tasks WHERE id = ?", String::class.java, failed))

        val notFailed = send(HttpMethod.POST, "/api/sheets/tasks/$failed/retry")
        assertEquals(409, notFailed.status)
        assertEquals("TASK_NOT_FAILED", notFailed.body["code"].asText())

        val missing = send(HttpMethod.POST, "/api/sheets/tasks/${UUID.randomUUID()}/retry")
        assertEquals(404, missing.status)
        assertEquals("TASK_NOT_FOUND", missing.body["code"].asText())
    }

    @Test
    fun `every failed task can be retried at once`() {
        repeat(3) { task("FAILED", 1) }
        task("DONE", 1)

        val r = send(HttpMethod.POST, "/api/sheets/tasks/retry-failed")

        assertEquals(202, r.status)
        assertEquals(3, r.body["requeued"].asInt())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sheet_tasks WHERE status = 'FAILED'", Int::class.java))
        assertEquals(0, send(HttpMethod.POST, "/api/sheets/tasks/retry-failed").body["requeued"].asInt())
    }
}
