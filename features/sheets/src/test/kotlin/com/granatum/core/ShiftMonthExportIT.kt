package com.granatum.core

import com.granatum.core.domain.contract.ShiftSheetMonth
import com.granatum.core.domain.contract.ShiftSheetRow
import com.granatum.core.domain.port.SheetCell
import com.granatum.core.domain.type.Role
import com.granatum.core.scheduling.SheetTaskWorker
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * US2: exporting a month of shifts to the Google spreadsheet (FR-009 to
 * FR-014), with the fake client and fake `timetracking` contracts. The worker
 * is driven by hand (`runOnce`), as `sheets.worker.immediate` is off here.
 */
@SpringBootTest(classes = [SheetsTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WithFakeSheetsClient::class, SheetsPermissiveSecurityTestConfig::class)
class ShiftMonthExportIT : SheetsHttp() {

    @Autowired lateinit var client: FakeSheetsClient
    @Autowired lateinit var source: FakeShiftSheetSource
    @Autowired lateinit var recorder: FakeShiftExportRecorder
    @Autowired lateinit var worker: SheetTaskWorker
    @Autowired lateinit var jdbc: JdbcTemplate

    private val shiftsSheet = "1ShiftsSpreadsheet_abcdefghijk"
    private val invoicesSheet = "1InvoicesSpreadsheet_abcdefgh"
    private val october = YearMonth.of(2026, 10)

    private val header = listOf("Nombre", "Apellidos", "DNI", "Fecha", "Entrada", "Salida")

    @BeforeEach
    fun clean() {
        jdbc.update("DELETE FROM sheet_tasks")
        jdbc.update("DELETE FROM sheet_settings")
        jdbc.update(
            "INSERT INTO sheet_settings (id, shifts_spreadsheet_id, invoices_spreadsheet_id, configured_by, configured_at, version) " +
                "VALUES (1, ?, ?, ?, now(), 0)",
            shiftsSheet, invoicesSheet, UUID.randomUUID()
        )
        client.reset()
        source.reset()
        recorder.reset()
        source.months[october] = ShiftSheetMonth(
            listOf(
                ShiftSheetRow("Ana", "García López", "11111111H", LocalDate.parse("2026-10-01"), LocalTime.of(7, 5), LocalTime.of(15, 0)),
                ShiftSheetRow("Pedro Antiguo", null, "22222222J", LocalDate.parse("2026-10-01"), LocalTime.of(9, 0), null),
                ShiftSheetRow("Ana", "García López", "11111111H", LocalDate.parse("2026-10-02"), LocalTime.of(22, 0), LocalTime.of(6, 30))
            ),
            lastNamesPending = 1
        )
    }

    private fun texts(row: List<SheetCell>) = row.map { (it as SheetCell.Text).value }

    @Test
    fun `exporting a month rewrites its tab with the header and one row per shift, and records the export`() {
        val r = send(HttpMethod.POST, "/api/sheets/shifts/2026/10")
        assertEquals(202, r.status, r.raw)
        val taskId = r.body["taskId"].asText()

        assertEquals(1, worker.runOnce())

        val tab = client.tab(shiftsSheet, "2026-10")
        assertEquals(header, texts(tab[0]))
        assertEquals(listOf("Ana", "García López", "11111111H", "01/10/2026", "07:05", "15:00"), texts(tab[1]))
        assertEquals(listOf("Pedro Antiguo", "", "22222222J", "01/10/2026", "09:00", ""), texts(tab[2]))
        assertEquals(listOf("Ana", "García López", "11111111H", "02/10/2026", "22:00", "06:30"), texts(tab[3]))
        assertEquals(4, tab.size)
        assertFalse(client.calls.any { it.inTransaction }, "Google is never called inside a transaction")

        val task = send(HttpMethod.GET, "/api/sheets/tasks/$taskId").body
        assertEquals("DONE", task["status"].asText())
        assertEquals("SHIFT_MONTH", task["kind"].asText())
        assertEquals("2026-10", task["reference"].asText())
        assertEquals(3, task["rowCount"].asInt())
        assertEquals(1, task["lastNamesPending"].asInt())

        val recorded = recorder.recorded.single()
        assertEquals(admin, recorded.requesterId, "the ADMIN who asked, from requested_by")
        assertEquals(Role.ADMIN, recorded.requesterRole)
        assertEquals(october, recorded.month)
        assertEquals(3, recorded.rowCount)
        assertFalse(recorded.inTransaction)
        val canonical = tab.joinToString("") { row -> texts(row).joinToString("\t") + "\n" }
        val expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
        assertEquals(expected, recorded.fingerprint, "SHA-256 of the rows written, header included")
    }

    @Test
    fun `exporting again leaves exactly the same rows and the same fingerprint`() {
        send(HttpMethod.POST, "/api/sheets/shifts/2026/10")
        worker.runOnce()
        val first = client.tab(shiftsSheet, "2026-10")

        send(HttpMethod.POST, "/api/sheets/shifts/2026/10")
        worker.runOnce()

        assertEquals(first, client.tab(shiftsSheet, "2026-10"))
        assertEquals(2, recorder.recorded.size)
        assertEquals(recorder.recorded[0].fingerprint, recorder.recorded[1].fingerprint)
        assertEquals(setOf("2026-10"), client.spreadsheets[shiftsSheet]!!.keys, "no other tab is touched")
    }

    @Test
    fun `two requests for the same month before the worker runs are one task`() {
        val a = send(HttpMethod.POST, "/api/sheets/shifts/2026/10").body["taskId"].asText()
        val b = send(HttpMethod.POST, "/api/sheets/shifts/2026/10").body["taskId"].asText()
        assertEquals(a, b)
        assertEquals(1, worker.runOnce())
    }

    @Test
    fun `a month without shifts leaves only the header`() {
        send(HttpMethod.POST, "/api/sheets/shifts/2026/9")
        worker.runOnce()
        assertEquals(listOf(header), client.tab(shiftsSheet, "2026-09").map(::texts))
        assertEquals(0, recorder.recorded.single().rowCount)
    }

    @Test
    fun `the current month can be exported, a future one cannot`() {
        val now = YearMonth.now(ZoneId.of("Europe/Madrid"))
        assertEquals(202, send(HttpMethod.POST, "/api/sheets/shifts/${now.year}/${now.monthValue}").status)

        val next = now.plusMonths(1)
        val r = send(HttpMethod.POST, "/api/sheets/shifts/${next.year}/${next.monthValue}")
        assertEquals(400, r.status, r.raw)
        assertEquals("INVALID_PERIOD", r.body["code"].asText())
    }

    @Test
    fun `a month that does not exist or is outside the retention period is refused`() {
        listOf("2026/13", "2026/0", "0/5").forEach {
            val r = send(HttpMethod.POST, "/api/sheets/shifts/$it")
            assertEquals(400, r.status, "$it: ${r.raw}")
            assertEquals("INVALID_PERIOD", r.body["code"].asText())
        }

        source.cutoff = LocalDate.parse("2026-02-10")
        val old = send(HttpMethod.POST, "/api/sheets/shifts/2026/1")
        assertEquals(400, old.status)
        assertEquals("INVALID_PERIOD", old.body["code"].asText())
        assertEquals(202, send(HttpMethod.POST, "/api/sheets/shifts/2026/2").status, "a month partly in the period is allowed")
    }

    @Test
    fun `without a shifts spreadsheet the export asks to configure it first`() {
        jdbc.update("DELETE FROM sheet_settings")
        val r = send(HttpMethod.POST, "/api/sheets/shifts/2026/10")
        assertEquals(409, r.status, r.raw)
        assertEquals("SHEETS_NOT_CONFIGURED", r.body["code"].asText())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sheet_tasks", Int::class.java))
    }

    @Test
    fun `without Google credentials the export is unavailable`() {
        client.active = false
        val r = send(HttpMethod.POST, "/api/sheets/shifts/2026/10")
        assertEquals(503, r.status, r.raw)
        assertEquals("SHEETS_UNAVAILABLE", r.body["code"].asText())
    }

    @Test
    fun `if the spreadsheet is unset before the task runs, it fails with a readable reason`() {
        val taskId = send(HttpMethod.POST, "/api/sheets/shifts/2026/10").body["taskId"].asText()
        jdbc.update("DELETE FROM sheet_settings")

        worker.runOnce()

        val task = send(HttpMethod.GET, "/api/sheets/tasks/$taskId").body
        assertEquals("FAILED", task["status"].asText())
        assertTrue(task["lastError"].asText().contains("hoja de fichajes"), task["lastError"].asText())
        assertTrue(recorder.recorded.isEmpty())
    }
}
