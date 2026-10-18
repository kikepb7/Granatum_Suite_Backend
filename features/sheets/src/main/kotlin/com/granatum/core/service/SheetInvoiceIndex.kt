package com.granatum.core.service

import com.granatum.core.infrastructure.database.entities.SheetInvoiceRowEntity
import com.granatum.core.infrastructure.database.repositories.SheetInvoiceRowRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** Where a written invoice lives: its spreadsheet and its tab (`sheet_invoice_rows`, research.md D-004). */
data class InvoiceLocation(val spreadsheetId: String, val tab: String)

/**
 * `sheet_invoice_rows`, for the invoice writers. Each method is one short
 * transaction: the writers call Google between them, never inside one
 * (research.md D-003).
 */
@Service
class SheetInvoiceIndex(
    private val rows: SheetInvoiceRowRepository,
    private val clock: Clock = Clock.systemUTC()
) {

    @Transactional(readOnly = true)
    fun find(invoiceId: UUID): InvoiceLocation? =
        rows.findById(invoiceId).map { InvoiceLocation(it.spreadsheetId, it.tab) }.orElse(null)

    @Transactional(readOnly = true)
    fun findAll(invoiceIds: Collection<UUID>): Map<UUID, InvoiceLocation> =
        if (invoiceIds.isEmpty()) emptyMap()
        else rows.findAllByInvoiceIdIn(invoiceIds).associate { it.invoiceId to InvoiceLocation(it.spreadsheetId, it.tab) }

    @Transactional
    fun record(invoiceId: UUID, location: InvoiceLocation) {
        val now = clock.instant()
        val row = rows.findById(invoiceId).orElse(null)
        if (row == null) {
            rows.save(SheetInvoiceRowEntity(invoiceId, location.spreadsheetId, location.tab, now))
        } else {
            row.spreadsheetId = location.spreadsheetId
            row.tab = location.tab
            row.writtenAt = now
        }
    }

    @Transactional
    fun forget(invoiceId: UUID) {
        if (rows.findById(invoiceId).isPresent) rows.deleteById(invoiceId)
    }

    /**
     * After a tab was rewritten whole: [invoiceIds] are now exactly the
     * invoices in [location]. Entries that pointed to it and are not among them
     * are gone from the sheet, so they are forgotten.
     */
    @Transactional
    fun replaceTab(location: InvoiceLocation, invoiceIds: Collection<UUID>) {
        val keep = invoiceIds.toSet()
        rows.findAllBySpreadsheetIdAndTab(location.spreadsheetId, location.tab)
            .filter { it.invoiceId !in keep }
            .forEach { rows.deleteById(it.invoiceId) }
        invoiceIds.forEach { record(it, location) }
    }
}
