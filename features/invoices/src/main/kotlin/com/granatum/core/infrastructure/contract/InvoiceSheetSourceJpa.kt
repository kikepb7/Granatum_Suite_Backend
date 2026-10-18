package com.granatum.core.infrastructure.contract

import com.granatum.core.domain.contract.InvoiceSheetRow
import com.granatum.core.domain.contract.InvoiceSheetSource
import com.granatum.core.domain.contract.InvoiceSheetType
import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.model.InvoiceType
import com.granatum.core.domain.type.EntityId
import com.granatum.core.infrastructure.database.entities.InvoiceEntity
import com.granatum.core.infrastructure.database.repositories.InvoiceRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.YearMonth

/**
 * This module's side of the `InvoiceSheetSource` contract (feature 012, US3,
 * research.md D-002): the **confirmed** invoices only - a draft never reaches
 * the spreadsheet (FR-017) - with base, VAT and surcharge summed over the VAT
 * lines, as the reports do.
 *
 * A confirmed invoice always has an issue date and a type (the validator and
 * `InvoiceReview.confirm` see to it); one without them could not be placed in a
 * month, so it is left out rather than guessed.
 */
@Component
class InvoiceSheetSourceJpa(private val invoices: InvoiceRepository) : InvoiceSheetSource {

    @Transactional(readOnly = true)
    override fun row(invoiceId: EntityId): InvoiceSheetRow? =
        invoices.findById(invoiceId).orElse(null)
            ?.takeIf { it.status == InvoiceStatus.CONFIRMED }
            ?.let(::toRow)

    @Transactional(readOnly = true)
    override fun month(month: YearMonth): List<InvoiceSheetRow> =
        invoices.findConfirmedBetween(month.atDay(1), month.atEndOfMonth())
            .mapNotNull(::toRow)
            .sortedWith(compareBy<InvoiceSheetRow>({ it.issueDate }, { it.number.orEmpty() }, { it.id.toString() }))

    private fun toRow(f: InvoiceEntity): InvoiceSheetRow? {
        val issueDate = f.issueDate ?: return null
        val type = f.type ?: return null
        val zero = BigDecimal.ZERO.setScale(2)
        val base = f.vatLines.fold(zero) { sum, l -> sum + l.base }
        val vat = f.vatLines.fold(zero) { sum, l -> sum + l.vatAmount }
        val surcharge = f.vatLines.fold(zero) { sum, l -> sum + l.surcharge }
        return InvoiceSheetRow(
            id = f.id,
            issueDate = issueDate,
            type = when (type) {
                InvoiceType.ISSUED -> InvoiceSheetType.ISSUED
                InvoiceType.RECEIVED -> InvoiceSheetType.RECEIVED
            },
            issuerName = f.issuerName,
            issuerNif = f.issuerNif,
            recipientName = f.recipientName,
            recipientNif = f.recipientNif,
            number = f.number,
            concept = f.concept,
            base = base,
            vatAmount = vat,
            surcharge = surcharge,
            withholdings = f.withholdings,
            // Required to confirm; the sum is only a guard for an old row.
            total = f.total ?: (base + vat + surcharge - f.withholdings)
        )
    }
}
