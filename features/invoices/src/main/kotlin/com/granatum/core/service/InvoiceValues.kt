package com.granatum.core.service

import com.granatum.core.domain.model.Invoice
import com.granatum.core.domain.model.VatLine
import com.granatum.core.domain.model.InvoiceType
import java.math.BigDecimal
import java.time.LocalDate

/** Everything a reviewer can set on an invoice (contracts/README.md, PUT). The breakdown is replaced whole. */
data class InvoiceValues(
    val type: InvoiceType?,
    val issuerName: String?,
    val issuerNif: String?,
    val recipientName: String?,
    val recipientNif: String?,
    val number: String?,
    val issueDate: LocalDate?,
    val concept: String?,
    val currency: String,
    val vatLines: List<VatLine>,
    val withholdings: BigDecimal,
    val total: BigDecimal?,
    val corrective: Boolean
) {
    override fun toString(): String = "InvoiceValues(***)"

    companion object {
        fun from(f: Invoice) = InvoiceValues(
            f.type, f.issuer?.name, f.issuer?.nif, f.recipient?.name, f.recipient?.nif, f.number,
            f.issueDate, f.concept, f.currency, f.vatLines, f.withholdings, f.total, f.corrective
        )
    }
}
