package com.granatum.core.service

import com.granatum.core.domain.event.InvoiceSheetChange
import com.granatum.core.domain.model.SheetTaskKind
import com.granatum.core.domain.port.SheetsClient
import com.granatum.core.scheduling.SheetTaskWorker
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Queues the copy of an invoice when it changes (US3, research.md D-003).
 *
 * `BEFORE_COMMIT`, not `AFTER_COMMIT`: the task is inserted **inside** the
 * transaction that confirms, corrects or discards the invoice, so either both
 * are committed or neither - a crash between the commit and the insert cannot
 * lose the copy. Inserting a local row is not writing to Google, so the change
 * still completes whatever Google does (FR-020). An event published outside a
 * transaction is ignored: there would be nothing to be atomic with.
 *
 * Nothing is queued without Google credentials or without a receipts
 * spreadsheet: invoices confirmed then are resynced by month, if wanted.
 *
 * Once the change commits, the worker is kicked, so the row is written in
 * seconds rather than at the next pass.
 */
@Component
class InvoiceSheetChangeListener(
    private val client: SheetsClient,
    private val settings: SheetSettingsService,
    private val queue: SheetTaskQueue,
    private val worker: SheetTaskWorker
) {

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun on(change: InvoiceSheetChange) {
        if (!client.active) return
        settings.find()?.invoicesSpreadsheetId ?: return
        queue.enqueue(SheetTaskKind.INVOICE, change.invoiceId.toString(), null)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = worker.kick()
        })
    }
}
