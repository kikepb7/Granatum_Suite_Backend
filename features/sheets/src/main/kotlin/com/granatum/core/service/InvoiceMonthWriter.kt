package com.granatum.core.service

import com.granatum.core.domain.contract.InvoiceSheetSource
import com.granatum.core.domain.model.SheetTask
import com.granatum.core.domain.model.SheetTaskKind
import com.granatum.core.domain.port.SheetsClient
import org.springframework.stereotype.Component
import java.time.YearMonth

/**
 * Resyncs a month of receipts (`INVOICE_MONTH`, reference `YYYY-MM`; FR-018,
 * research.md D-004): rewrites the whole tab with every confirmed invoice
 * issued that month, ids in the hidden column A, and leaves
 * `sheet_invoice_rows` saying exactly that.
 *
 * An invoice of the month that the index places in another tab of the same
 * spreadsheet - its move was never written - is removed from there first, so it
 * does not stay in two tabs.
 */
@Component
class InvoiceMonthWriter(
    private val source: InvoiceSheetSource,
    private val index: SheetInvoiceIndex,
    private val settings: SheetSettingsService,
    private val client: SheetsClient
) : SheetTaskHandler {

    override val kind = SheetTaskKind.INVOICE_MONTH

    override fun handle(task: SheetTask): SheetTaskResult {
        val month = YearMonth.parse(task.reference)
        val spreadsheetId = settings.find()?.invoicesSpreadsheetId
            ?: throw SheetTaskAbortedException("La hoja de recibos no está configurada")
        val target = InvoiceLocation(spreadsheetId, month.toString())

        val rows = source.month(month)
        index.findAll(rows.map { it.id })
            .filter { (_, at) -> at.spreadsheetId == spreadsheetId && at.tab != target.tab }
            .forEach { (id, at) -> client.removeInvoiceRow(at.spreadsheetId, at.tab, id) }

        client.rewriteTab(
            spreadsheetId, target.tab, InvoiceSheetColumns.HEADER,
            rows.map(InvoiceSheetColumns::fullRow), hiddenIdColumn = true
        )
        index.replaceTab(target, rows.map { it.id })
        return SheetTaskResult(rowCount = rows.size)
    }
}
