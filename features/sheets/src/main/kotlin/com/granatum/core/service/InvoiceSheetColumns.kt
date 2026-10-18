package com.granatum.core.service

import com.granatum.core.domain.contract.InvoiceSheetRow
import com.granatum.core.domain.contract.InvoiceSheetType
import com.granatum.core.domain.port.SheetCell
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * The layout of a receipts tab (data-model.md): the invoice id in the hidden
 * column A, then the data. Dates as text `dd/MM/yyyy`, amounts as numbers the
 * sheet can sum, everything written `RAW` (research.md D-004).
 */
object InvoiceSheetColumns {

    val HEADER = listOf(
        "Id", "Fecha", "Tipo", "Emisor", "NIF emisor", "Receptor", "NIF receptor",
        "Número", "Concepto", "Base", "IVA", "Recargo", "Retenciones", "Total"
    )

    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** The tab of an invoice: the month of its issue date, `YYYY-MM`. */
    fun tab(row: InvoiceSheetRow): String = YearMonth.from(row.issueDate).toString()

    /** The cells after the id. */
    fun cells(row: InvoiceSheetRow): List<SheetCell> = listOf(
        SheetCell.text(row.issueDate.format(DATE)),
        SheetCell.text(
            when (row.type) {
                InvoiceSheetType.ISSUED -> "Emitida"
                InvoiceSheetType.RECEIVED -> "Recibida"
            }
        ),
        SheetCell.text(row.issuerName),
        SheetCell.text(row.issuerNif),
        SheetCell.text(row.recipientName),
        SheetCell.text(row.recipientNif),
        SheetCell.text(row.number),
        SheetCell.text(row.concept),
        SheetCell.number(row.base),
        SheetCell.number(row.vatAmount),
        SheetCell.number(row.surcharge),
        SheetCell.number(row.withholdings),
        SheetCell.number(row.total)
    )

    /** The whole row, id first, as `rewriteTab(hiddenIdColumn = true)` expects it. */
    fun fullRow(row: InvoiceSheetRow): List<SheetCell> = listOf(SheetCell.text(row.id.toString())) + cells(row)
}
