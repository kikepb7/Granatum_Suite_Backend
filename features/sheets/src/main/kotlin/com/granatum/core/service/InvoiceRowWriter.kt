package com.granatum.core.service

import com.granatum.core.domain.contract.InvoiceSheetSource
import com.granatum.core.domain.model.SheetTask
import com.granatum.core.domain.model.SheetTaskKind
import com.granatum.core.domain.port.SheetsClient
import com.granatum.core.domain.port.SheetsPermanentException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Writes one invoice (`INVOICE`, reference its id; US3, research.md D-004),
 * with its **current** state, whatever the number of changes since it was
 * queued (FR-021):
 *
 * - **confirmed**: upserted, by id in column A, in the tab of its issue month.
 *   If `sheet_invoice_rows` places it in another tab or another spreadsheet (its
 *   month was corrected, or the ADMIN changed the receipts spreadsheet), it is
 *   removed from there first, so it is never in two places;
 * - **not confirmed** (discarded): removed from where it was written, and its
 *   entry forgotten. Never written if it never was (FR-017).
 */
@Component
class InvoiceRowWriter(
    private val source: InvoiceSheetSource,
    private val index: SheetInvoiceIndex,
    private val settings: SheetSettingsService,
    private val client: SheetsClient
) : SheetTaskHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    override val kind = SheetTaskKind.INVOICE

    override fun handle(task: SheetTask): SheetTaskResult {
        val id = UUID.fromString(task.reference)
        val spreadsheetId = settings.find()?.invoicesSpreadsheetId
            ?: throw SheetTaskAbortedException("La hoja de recibos no está configurada")
        val previous = index.find(id)
        val row = source.row(id)

        if (row == null) {
            if (previous != null) {
                client.removeInvoiceRow(previous.spreadsheetId, previous.tab, id)
                index.forget(id)
            }
            return SheetTaskResult(rowCount = 0)
        }

        val target = InvoiceLocation(spreadsheetId, InvoiceSheetColumns.tab(row))
        if (previous != null && previous != target) removeFromOld(previous, target, id)
        client.upsertInvoiceRow(target.spreadsheetId, target.tab, InvoiceSheetColumns.HEADER, id, InvoiceSheetColumns.cells(row))
        index.record(id, target)
        return SheetTaskResult(rowCount = 1)
    }

    /**
     * A spreadsheet that is no longer the receipts one may have been unshared
     * or deleted: it is not ours any more, and refusing there must not keep the
     * invoice out of the current one. In the current spreadsheet, a refusal is a
     * real failure.
     */
    private fun removeFromOld(previous: InvoiceLocation, target: InvoiceLocation, id: UUID) {
        try {
            client.removeInvoiceRow(previous.spreadsheetId, previous.tab, id)
        } catch (e: SheetsPermanentException) {
            if (previous.spreadsheetId == target.spreadsheetId) throw e
            log.info("No se pudo quitar la factura {} de la hoja de recibos anterior: {}", id, e.reason)
        }
    }
}
