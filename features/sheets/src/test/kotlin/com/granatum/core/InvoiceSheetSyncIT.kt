package com.granatum.core

import com.granatum.core.domain.contract.InvoiceSheetRow
import com.granatum.core.domain.contract.InvoiceSheetType
import com.granatum.core.domain.event.InvoiceSheetChange
import com.granatum.core.domain.port.SheetCell
import com.granatum.core.domain.port.SheetsTransientException
import com.granatum.core.scheduling.SheetTaskWorker
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * US3: confirmed invoices copied on their own to the receipts spreadsheet
 * (FR-015 to FR-018, FR-020, FR-021), with the fake client and a fake
 * `InvoiceSheetSource`. `invoices` is not here (principle I): the test
 * publishes `InvoiceSheetChange` itself, inside transactions, as
 * `InvoiceReview` does.
 */
@SpringBootTest(classes = [SheetsTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WithFakeSheetsClient::class, SheetsPermissiveSecurityTestConfig::class)
class InvoiceSheetSyncIT : SheetsHttp() {

    @Autowired lateinit var client: FakeSheetsClient
    @Autowired lateinit var source: FakeInvoiceSheetSource
    @Autowired lateinit var worker: SheetTaskWorker
    @Autowired lateinit var events: ApplicationEventPublisher
    @Autowired lateinit var transactions: TransactionTemplate
    @Autowired lateinit var jdbc: JdbcTemplate

    private val shiftsSheet = "1ShiftsSpreadsheet_abcdefghijk"
    private val invoicesSheet = "1InvoicesSpreadsheet_abcdefgh"

    private val header = listOf(
        "Id", "Fecha", "Tipo", "Emisor", "NIF emisor", "Receptor", "NIF receptor",
        "Número", "Concepto", "Base", "IVA", "Recargo", "Retenciones", "Total"
    )

    @BeforeEach
    fun clean() {
        jdbc.update("DELETE FROM sheet_tasks")
        jdbc.update("DELETE FROM sheet_invoice_rows")
        configure(invoicesSheet)
        client.reset()
        source.reset()
    }

    private fun configure(invoices: String?) {
        jdbc.update("DELETE FROM sheet_settings")
        jdbc.update(
            "INSERT INTO sheet_settings (id, shifts_spreadsheet_id, invoices_spreadsheet_id, configured_by, configured_at, version) " +
                "VALUES (1, ?, ?, ?, now(), 0)",
            shiftsSheet, invoices, UUID.randomUUID()
        )
    }

    private fun invoice(
        id: UUID = UUID.randomUUID(),
        issueDate: String = "2026-10-02",
        type: InvoiceSheetType = InvoiceSheetType.RECEIVED,
        concept: String = "Rosas rojas"
    ) = InvoiceSheetRow(
        id = id,
        issueDate = LocalDate.parse(issueDate),
        type = type,
        issuerName = "Flores del Sur S.L.",
        issuerNif = "A58818501",
        recipientName = "Floristeria Granatum S.L.",
        recipientNif = "B12345674",
        number = "2026-0815",
        concept = concept,
        base = BigDecimal("150.00"),
        vatAmount = BigDecimal("26.00"),
        surcharge = BigDecimal("0.00"),
        withholdings = BigDecimal("0.00"),
        total = BigDecimal("176.00")
    )

    /** What `InvoiceReview` does: publish inside the transaction of the change. */
    private fun change(id: UUID, rollback: Boolean = false) {
        transactions.executeWithoutResult { status ->
            events.publishEvent(InvoiceSheetChange(id))
            if (rollback) status.setRollbackOnly()
        }
    }

    private fun tasks(): List<Map<String, Any?>> = jdbc.queryForList("SELECT * FROM sheet_tasks")

    private fun indexed(id: UUID): Map<String, Any?>? =
        jdbc.queryForList("SELECT * FROM sheet_invoice_rows WHERE invoice_id = ?", id).singleOrNull()

    private fun texts(row: List<SheetCell>) = row.map {
        when (it) {
            is SheetCell.Text -> it.value
            is SheetCell.Number -> it.value.toPlainString()
        }
    }

    // --- atomicity ----------------------------------------------------------

    @Test
    fun `a change in a transaction that rolls back leaves no task`() {
        change(UUID.randomUUID(), rollback = true)
        assertTrue(tasks().isEmpty())
    }

    @Test
    fun `a committed change leaves one INVOICE task for that invoice`() {
        val id = UUID.randomUUID()
        change(id)
        val task = tasks().single()
        assertEquals("INVOICE", task["kind"])
        assertEquals(id.toString(), task["reference"])
        assertEquals("PENDING", task["status"])
        assertEquals(null, task["requested_by"], "an automatic copy has no requester")
    }

    @Test
    fun `a change published outside any transaction is ignored`() {
        events.publishEvent(InvoiceSheetChange(UUID.randomUUID()))
        assertTrue(tasks().isEmpty())
    }

    @Test
    fun `without the integration or without a receipts spreadsheet nothing is queued`() {
        client.active = false
        change(UUID.randomUUID())
        assertTrue(tasks().isEmpty(), "no Google credentials")

        client.active = true
        configure(invoices = null)
        change(UUID.randomUUID())
        assertTrue(tasks().isEmpty(), "no receipts spreadsheet")

        jdbc.update("DELETE FROM sheet_settings")
        change(UUID.randomUUID())
        assertTrue(tasks().isEmpty(), "nothing configured")
    }

    // --- writing ------------------------------------------------------------

    @Test
    fun `a confirmed invoice is written in the tab of its issue month with the receipts columns`() {
        val id = UUID.randomUUID()
        source.confirmed[id] = invoice(id)
        change(id)

        assertEquals(1, worker.runOnce())

        val tab = client.tab(invoicesSheet, "2026-10")
        assertEquals(header, texts(tab[0]))
        val row = tab[1]
        assertEquals(
            listOf(
                id.toString(), "02/10/2026", "Recibida", "Flores del Sur S.L.", "A58818501",
                "Floristeria Granatum S.L.", "B12345674", "2026-0815", "Rosas rojas",
                "150.00", "26.00", "0.00", "0.00", "176.00"
            ),
            texts(row)
        )
        row.drop(9).forEach { assertTrue(it is SheetCell.Number, "amounts are numbers the sheet can sum") }
        row.take(9).forEach { assertTrue(it is SheetCell.Text) }
        assertFalse(client.calls.any { it.inTransaction }, "Google is never called inside a transaction")

        val index = indexed(id)!!
        assertEquals(invoicesSheet, index["spreadsheet_id"])
        assertEquals("2026-10", index["tab"])
        assertEquals("DONE", tasks().single()["status"])
    }

    @Test
    fun `an issued invoice says so`() {
        val id = UUID.randomUUID()
        source.confirmed[id] = invoice(id, type = InvoiceSheetType.ISSUED)
        change(id)
        worker.runOnce()
        assertEquals("Emitida", texts(client.tab(invoicesSheet, "2026-10")[1])[2])
    }

    @Test
    fun `a correction updates the row in place`() {
        val id = UUID.randomUUID()
        source.confirmed[id] = invoice(id)
        change(id)
        worker.runOnce()

        source.confirmed[id] = invoice(id, concept = "Rosas blancas")
        change(id)
        worker.runOnce()

        val tab = client.tab(invoicesSheet, "2026-10")
        assertEquals(2, tab.size, "the header and the one row: no duplicate")
        assertEquals("Rosas blancas", texts(tab[1])[8])
    }

    @Test
    fun `a correction that changes the month moves the row to the new tab`() {
        val id = UUID.randomUUID()
        source.confirmed[id] = invoice(id, issueDate = "2026-10-02")
        change(id)
        worker.runOnce()

        source.confirmed[id] = invoice(id, issueDate = "2026-09-28")
        change(id)
        worker.runOnce()

        assertEquals(listOf(header), client.tab(invoicesSheet, "2026-10").map(::texts), "gone from the old tab")
        assertEquals(id.toString(), texts(client.tab(invoicesSheet, "2026-09")[1])[0])
        assertEquals("2026-09", indexed(id)!!["tab"])
        val removal = client.calls.indexOfFirst { it.operation == "removeInvoiceRow" && it.tab == "2026-10" }
        val upsert = client.calls.indexOfLast { it.operation == "upsertInvoiceRow" && it.tab == "2026-09" }
        assertTrue(removal in 0 until upsert, "removed from the old tab first")
    }

    @Test
    fun `after the receipts spreadsheet changes, the row leaves the old one and goes to the new one`() {
        val id = UUID.randomUUID()
        source.confirmed[id] = invoice(id)
        change(id)
        worker.runOnce()

        val newSheet = "1NewInvoicesSpreadsheet_abcdef"
        configure(newSheet)
        change(id)
        worker.runOnce()

        assertEquals(1, client.tab(invoicesSheet, "2026-10").size, "only the header left in the old spreadsheet")
        assertEquals(2, client.tab(newSheet, "2026-10").size)
        assertEquals(newSheet, indexed(id)!!["spreadsheet_id"])
    }

    // --- removal ------------------------------------------------------------

    @Test
    fun `an invoice no longer confirmed is removed, with its index entry`() {
        val id = UUID.randomUUID()
        source.confirmed[id] = invoice(id)
        change(id)
        worker.runOnce()

        source.confirmed.remove(id) // discarded
        change(id)
        worker.runOnce()

        assertEquals(1, client.tab(invoicesSheet, "2026-10").size)
        assertEquals(null, indexed(id))
        assertEquals(listOf("DONE", "DONE"), tasks().map { it["status"] })
    }

    @Test
    fun `a change of an invoice never written and not confirmed touches nothing`() {
        change(UUID.randomUUID())
        worker.runOnce()
        assertTrue(client.calls.isEmpty())
        assertEquals("DONE", tasks().single()["status"])
    }

    // --- coalescing and failures ---------------------------------------------

    @Test
    fun `ten changes in a row are one write, with the final state`() {
        val id = UUID.randomUUID()
        repeat(10) {
            source.confirmed[id] = invoice(id, concept = "Versión $it")
            change(id)
        }

        assertEquals(1, worker.runOnce())

        assertEquals(1, client.calls.count { it.operation == "upsertInvoiceRow" })
        assertEquals("Versión 9", texts(client.tab(invoicesSheet, "2026-10")[1])[8])
    }

    @Test
    fun `when Google does not answer the task waits and the row is written once it does`() {
        val id = UUID.randomUUID()
        source.confirmed[id] = invoice(id)
        change(id)
        client.nextFailures += SheetsTransientException("Google no responde")

        worker.runOnce()
        assertEquals("PENDING", tasks().single()["status"])
        assertEquals(1, tasks().single()["attempts"])

        jdbc.update("UPDATE sheet_tasks SET next_attempt_at = now()")
        worker.runOnce()
        assertEquals("DONE", tasks().single()["status"])
        assertEquals(2, client.tab(invoicesSheet, "2026-10").size)
    }

    // --- resync -------------------------------------------------------------

    @Test
    fun `resyncing a month rewrites its tab with every confirmed invoice and updates the index`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val other = UUID.randomUUID()
        source.confirmed[a] = invoice(a, issueDate = "2026-10-20")
        source.confirmed[b] = invoice(b, issueDate = "2026-10-01")
        source.confirmed[other] = invoice(other, issueDate = "2026-11-01")
        // A stale row, left by hand or by an invoice since discarded.
        val gone = UUID.randomUUID()
        client.upsertInvoiceRow(invoicesSheet, "2026-10", header, gone, listOf(SheetCell.text("x")))
        jdbc.update("INSERT INTO sheet_invoice_rows (invoice_id, spreadsheet_id, tab, written_at) VALUES (?, ?, '2026-10', now())", gone, invoicesSheet)
        client.calls.clear()

        val r = send(HttpMethod.POST, "/api/sheets/invoices/2026/10/resync")
        assertEquals(202, r.status, r.raw)
        val taskId = r.body["taskId"].asText()

        worker.runOnce()

        val tab = client.tab(invoicesSheet, "2026-10")
        assertEquals(header, texts(tab[0]))
        assertEquals(listOf(b, a).map { it.toString() }, tab.drop(1).map { texts(it)[0] }, "ordered by issue date")
        assertEquals("rewriteTab", client.calls.single().operation)
        assertEquals("2026-10", indexed(a)!!["tab"])
        assertEquals("2026-10", indexed(b)!!["tab"])
        assertEquals(null, indexed(gone))
        assertEquals(null, indexed(other))

        val task = send(HttpMethod.GET, "/api/sheets/tasks/$taskId").body
        assertEquals("INVOICE_MONTH", task["kind"].asText())
        assertEquals("DONE", task["status"].asText())
        assertEquals(2, task["rowCount"].asInt())
        assertEquals(admin.toString(), jdbc.queryForObject("SELECT requested_by::text FROM sheet_tasks WHERE id = ?::uuid", String::class.java, taskId))
    }

    @Test
    fun `resyncing moves out an invoice the index places in another tab`() {
        val id = UUID.randomUUID()
        source.confirmed[id] = invoice(id, issueDate = "2026-10-05")
        client.upsertInvoiceRow(invoicesSheet, "2026-09", header, id, listOf(SheetCell.text("viejo")))
        jdbc.update("INSERT INTO sheet_invoice_rows (invoice_id, spreadsheet_id, tab, written_at) VALUES (?, ?, '2026-09', now())", id, invoicesSheet)

        send(HttpMethod.POST, "/api/sheets/invoices/2026/10/resync")
        worker.runOnce()

        assertEquals(1, client.tab(invoicesSheet, "2026-09").size)
        assertEquals(2, client.tab(invoicesSheet, "2026-10").size)
        assertEquals("2026-10", indexed(id)!!["tab"])
    }

    @Test
    fun `resync refuses a future or invalid month, and asks for the receipts spreadsheet`() {
        assertEquals("INVALID_PERIOD", send(HttpMethod.POST, "/api/sheets/invoices/2026/13/resync").body["code"].asText())
        val next = java.time.YearMonth.now(java.time.ZoneId.of("Europe/Madrid")).plusMonths(1)
        val future = send(HttpMethod.POST, "/api/sheets/invoices/${next.year}/${next.monthValue}/resync")
        assertEquals(400, future.status)
        assertEquals("INVALID_PERIOD", future.body["code"].asText())

        configure(invoices = null)
        val r = send(HttpMethod.POST, "/api/sheets/invoices/2026/10/resync")
        assertEquals(409, r.status)
        assertEquals("SHEETS_NOT_CONFIGURED", r.body["code"].asText())

        client.active = false
        assertEquals(503, send(HttpMethod.POST, "/api/sheets/invoices/2026/10/resync").status)
    }
}
