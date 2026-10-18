package com.granatum.core

import com.granatum.core.domain.port.DisabledSheetsClient
import com.granatum.core.domain.port.SheetCell
import com.granatum.core.domain.port.SheetsClient
import com.granatum.core.domain.port.SheetsUnavailableException
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The double of [SheetsClient] that every test of the suite uses: no test
 * calls Google (research.md D-009). In memory, scriptable per call, and it
 * records whether it was called with a transaction open - which must never
 * happen (research.md D-003).
 *
 * With [active] false it answers like [DisabledSheetsClient], the client of a
 * server without credentials.
 */
class FakeSheetsClient : SheetsClient {

    @Volatile override var active: Boolean = true

    @Volatile var email: String = "granatum-test@proyecto-test.iam.gserviceaccount.com"

    /** spreadsheet id → tab → rows (header first). */
    val spreadsheets = ConcurrentHashMap<String, MutableMap<String, MutableList<List<SheetCell>>>>()

    /** What [checkAccess] throws per spreadsheet id; absent = accessible. */
    val accessFailures = ConcurrentHashMap<String, RuntimeException>()

    /** Failures for the next calls, consumed in order, whatever the operation. */
    val nextFailures = ConcurrentLinkedDeque<RuntimeException>()

    /** Run inside every call, before answering: lets a test block or observe a call in flight. */
    @Volatile var onCall: (Call) -> Unit = {}

    data class Call(val operation: String, val spreadsheetId: String, val tab: String?, val inTransaction: Boolean)

    val calls = CopyOnWriteArrayList<Call>()

    private val disabled = DisabledSheetsClient()

    private fun record(operation: String, spreadsheetId: String, tab: String?) {
        if (!active) throw disabledFailure()
        val call = Call(operation, spreadsheetId, tab, TransactionSynchronizationManager.isActualTransactionActive())
        calls += call
        onCall(call)
        nextFailures.pollFirst()?.let { throw it }
    }

    private fun disabledFailure(): RuntimeException = SheetsUnavailableException()

    override fun serviceAccountEmail(): String {
        if (!active) return disabled.serviceAccountEmail()
        return email
    }

    override fun checkAccess(spreadsheetId: String) {
        record("checkAccess", spreadsheetId, null)
        accessFailures[spreadsheetId]?.let { throw it }
    }

    override fun rewriteTab(
        spreadsheetId: String,
        tab: String,
        header: List<String>,
        rows: List<List<SheetCell>>,
        hiddenIdColumn: Boolean
    ) {
        record("rewriteTab", spreadsheetId, tab)
        tabs(spreadsheetId)[tab] = (listOf(header.map { SheetCell.text(it) }) + rows).toMutableList()
    }

    override fun upsertInvoiceRow(spreadsheetId: String, tab: String, header: List<String>, invoiceId: UUID, row: List<SheetCell>) {
        record("upsertInvoiceRow", spreadsheetId, tab)
        val rows = tabs(spreadsheetId).getOrPut(tab) { mutableListOf(header.map { SheetCell.text(it) }) }
        val full = listOf(SheetCell.text(invoiceId.toString())) + row
        val index = rows.indexOfFirst { it.firstOrNull() == SheetCell.text(invoiceId.toString()) }
        if (index >= 0) rows[index] = full else rows += full
    }

    override fun removeInvoiceRow(spreadsheetId: String, tab: String, invoiceId: UUID) {
        record("removeInvoiceRow", spreadsheetId, tab)
        tabs(spreadsheetId)[tab]?.removeIf { it.firstOrNull() == SheetCell.text(invoiceId.toString()) }
    }

    /** The rows of a tab, header first; empty if the tab does not exist. */
    fun tab(spreadsheetId: String, tab: String): List<List<SheetCell>> = spreadsheets[spreadsheetId]?.get(tab).orEmpty()

    private fun tabs(spreadsheetId: String) = spreadsheets.getOrPut(spreadsheetId) { ConcurrentHashMap() }

    fun reset() {
        active = true
        email = "granatum-test@proyecto-test.iam.gserviceaccount.com"
        spreadsheets.clear()
        accessFailures.clear()
        nextFailures.clear()
        onCall = {}
        calls.clear()
    }
}

/** Registers the double in place of the real client. Import it in a test with `@Import`. */
@TestConfiguration
class WithFakeSheetsClient {
    @Bean
    @Primary
    fun fakeSheetsClient(): FakeSheetsClient = FakeSheetsClient()
}
