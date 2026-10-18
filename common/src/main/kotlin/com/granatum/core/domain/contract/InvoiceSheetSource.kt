package com.granatum.core.domain.contract

import com.granatum.core.domain.type.EntityId
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * The confirmed invoices as the receipts spreadsheet receives them (feature
 * 012, US3, research.md D-002).
 *
 * `invoices` implements it; `sheets` uses it and never mentions `invoices`
 * (constitution principle I). Only **confirmed** invoices exist here: a draft,
 * an invoice pending recognition or a discarded one is never written (FR-017).
 * Implementations read in their own short read-only transaction.
 */
interface InvoiceSheetSource {

    /** The row of the invoice, or null unless it exists and is `CONFIRMED`. */
    fun row(invoiceId: EntityId): InvoiceSheetRow?

    /**
     * Every confirmed invoice whose issue date falls in [month], ordered by
     * issue date, number and id.
     */
    fun month(month: YearMonth): List<InvoiceSheetRow>
}

/** Issued or received, seen from the company (`invoices.type`). */
enum class InvoiceSheetType { ISSUED, RECEIVED }

/**
 * One confirmed invoice. [base], [vatAmount] and [surcharge] are the sums of
 * its VAT lines; amounts are exact to the cent.
 *
 * `toString` hides the parties' names and tax ids: a self-employed issuer's
 * name and NIF are personal data, and a data class would print them in any log
 * line (principle VI).
 */
data class InvoiceSheetRow(
    val id: EntityId,
    val issueDate: LocalDate,
    val type: InvoiceSheetType,
    val issuerName: String?,
    val issuerNif: String?,
    val recipientName: String?,
    val recipientNif: String?,
    val number: String?,
    val concept: String?,
    val base: BigDecimal,
    val vatAmount: BigDecimal,
    val surcharge: BigDecimal,
    val withholdings: BigDecimal,
    val total: BigDecimal
) {
    override fun toString(): String = "InvoiceSheetRow(id=$id, issueDate=$issueDate, type=$type)"
}
