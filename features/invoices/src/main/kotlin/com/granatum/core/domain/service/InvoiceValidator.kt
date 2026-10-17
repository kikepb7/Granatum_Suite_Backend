package com.granatum.core.domain.service

import com.granatum.core.domain.model.InvoiceNotice
import com.granatum.core.domain.model.InvoiceNoticeCode
import com.granatum.core.domain.model.Invoice
import com.granatum.core.validation.NifValidator
import java.math.BigDecimal
import java.time.LocalDate

/** What the validator needs from outside the invoice itself. */
data class InvoiceValidationContext(
    val today: LocalDate,
    /** Fields the last recognition could not read with certainty. */
    val uncertainFields: List<String> = emptyList(),
    /** Another confirmed invoice has the same issuer, number and date. */
    val duplicate: Boolean = false,
    val quarterClosed: Boolean = false,
    /** `NOT_AN_INVOICE` or `MULTIPLE_INVOICES` from the last recognition, if so. */
    val recognitionResult: String? = null,
    /** Neither tax id is the company's. */
    val notCompanyInvoice: Boolean = false
)

/**
 * The warnings of an invoice (FR-011 to FR-014, contracts/README.md): those that
 * **block** confirmation and those that only ask for a second look. Pure - no
 * database, no clock - so every rule has a unit test.
 *
 * Messages name fields and sums, never a party's name or tax id (FR-027).
 */
object InvoiceValidator {

    private val TOLERANCE = BigDecimal("0.01")

    fun validate(f: Invoice, ctx: InvoiceValidationContext): List<InvoiceNotice> = buildList {
        fun block(field: String, code: InvoiceNoticeCode, message: String) = add(InvoiceNotice(field, code, true, message))
        fun warn(field: String, code: InvoiceNoticeCode, message: String) = add(InvoiceNotice(field, code, false, message))

        // FR-013: what a confirmed invoice must have.
        if (f.issuer?.nif.isNullOrBlank()) block("issuer.nif", InvoiceNoticeCode.REQUIRED, "Falta el NIF del emisor")
        if (f.number.isNullOrBlank()) block("number", InvoiceNoticeCode.REQUIRED, "Falta el numero de factura")
        if (f.issueDate == null) block("issueDate", InvoiceNoticeCode.REQUIRED, "Falta la fecha de emision")
        if (f.vatLines.isEmpty()) block("vatLines", InvoiceNoticeCode.REQUIRED, "Falta al menos una base con su tipo de IVA")
        if (f.total == null) block("total", InvoiceNoticeCode.REQUIRED, "Falta el total")
        if (f.type == null) block("type", InvoiceNoticeCode.REQUIRED, "No se sabe si es emitida o recibida")

        // FR-012: control characters, the recipient's only if there is one.
        f.issuer?.nif?.takeIf { it.isNotBlank() }?.let {
            if (!NifValidator.isValid(NifValidator.normalizeTaxId(it))) {
                block("issuer.nif", InvoiceNoticeCode.INVALID_NIF, "El NIF del emisor no tiene un control valido")
            }
        }
        f.recipient?.nif?.takeIf { it.isNotBlank() }?.let {
            if (!NifValidator.isValid(NifValidator.normalizeTaxId(it))) {
                block("recipient.nif", InvoiceNoticeCode.INVALID_NIF, "El NIF del destinatario no tiene un control valido")
            }
        }

        // FR-011: bases + VAT + surcharge - withholdings = total, within a cent.
        val total = f.total
        if (total != null && f.vatLines.isNotEmpty()) {
            val calculated = f.vatLines.fold(BigDecimal.ZERO) { s, l -> s + l.base + l.vatAmount + l.surcharge } - f.withholdings
            if ((calculated - total).abs() > TOLERANCE) {
                block(
                    "total", InvoiceNoticeCode.TOTAL_MISMATCH,
                    "Bases + IVA + recargo - retenciones = ${calculated.setScale(2).toPlainString()}; " +
                        "el total es ${total.setScale(2).toPlainString()}"
                )
            }
        }

        if (!f.corrective) {
            val amounts = f.vatLines.flatMap { listOf(it.base, it.vatAmount, it.surcharge) } + listOfNotNull(total)
            if (amounts.any { it.signum() < 0 }) {
                block("total", InvoiceNoticeCode.NEGATIVE_AMOUNT, "Hay importes negativos y la factura no es rectificativa")
            }
        }

        // Research D-020: a line without VAT must say why.
        f.vatLines.forEachIndexed { i, l ->
            if (l.vatAmount.signum() == 0 && l.noVatCause == null) {
                block("vatLines[$i].noVatCause", InvoiceNoticeCode.MISSING_NO_VAT_CAUSE, "Una linea sin cuota de IVA tiene que decir por que")
            }
        }

        if (f.currency != "EUR") block("currency", InvoiceNoticeCode.CURRENCY, "Solo se confirman importes en euros; introducelos en euros")

        f.issueDate?.let { date ->
            if (date.isAfter(ctx.today)) warn("issueDate", InvoiceNoticeCode.FUTURE_DATE, "La fecha de emision es posterior a hoy")
            if (date.isBefore(ctx.today.minusYears(4))) warn("issueDate", InvoiceNoticeCode.OLD_DATE, "La fecha de emision tiene mas de cuatro anos")
        }

        // From the context.
        if (ctx.duplicate) block("number", InvoiceNoticeCode.DUPLICATE, "Ya hay una factura confirmada con el mismo emisor, numero y fecha")
        if (ctx.quarterClosed) block("issueDate", InvoiceNoticeCode.QUARTER_CLOSED, "Su trimestre esta cerrado")
        if (ctx.notCompanyInvoice) warn("type", InvoiceNoticeCode.NOT_COMPANY_INVOICE, "Ni el emisor ni el destinatario son la empresa")
        when (ctx.recognitionResult) {
            "NOT_AN_INVOICE" -> warn("document", InvoiceNoticeCode.NOT_AN_INVOICE, "El reconocimiento dice que el documento no es una factura")
            "MULTIPLE_INVOICES" -> warn("document", InvoiceNoticeCode.MULTIPLE_INVOICES, "El documento parece contener varias facturas; subelas por separado")
        }
        ctx.uncertainFields.forEach { warn(it, InvoiceNoticeCode.UNCERTAIN, "El reconocimiento no lo leyo con seguridad") }
    }

    fun blockers(notices: List<InvoiceNotice>) = notices.filter { it.blocking }
}
