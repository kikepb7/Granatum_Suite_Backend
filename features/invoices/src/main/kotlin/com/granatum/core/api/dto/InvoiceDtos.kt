package com.granatum.core.api.dto

import com.granatum.core.domain.model.InvoiceNotice
import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.model.Invoice
import com.granatum.core.domain.model.InvoiceType
import java.util.UUID

// DTOs of invoicing. Every one that carries a name, a tax id or an amount
// overrides toString(): at DEBUG, Spring MVC logs bodies through it (FR-027,
// the lesson of feature 002). Amounts travel as decimal strings
// (contracts/README.md): a JSON number could lose a cent on the client.

data class InvoiceUploadResultDto(val file: Int, val result: String, val invoiceId: UUID?)

data class InvoicePartyDto(
    @field:jakarta.validation.constraints.Size(max = 200) val name: String?,
    @field:jakarta.validation.constraints.Size(max = 20) val nif: String?
) {
    override fun toString(): String = "InvoicePartyDto(***)"
}

data class InvoiceVatLineDto(
    val vatRate: String,
    val base: String,
    val vatAmount: String,
    val surcharge: String,
    val noVatCause: String?
) {
    override fun toString(): String = "InvoiceVatLineDto(***)"
}

data class InvoiceRecognitionSummaryDto(val result: String, val uncertainFields: List<String>)

data class InvoiceNoticeDto(val field: String, val code: String, val blocking: Boolean, val message: String)

data class InvoiceDto(
    val id: UUID,
    val status: InvoiceStatus,
    val type: InvoiceType?,
    val version: Int,
    val issuer: InvoicePartyDto?,
    val recipient: InvoicePartyDto?,
    val number: String?,
    val issueDate: String?,
    val concept: String?,
    val currency: String,
    val corrective: Boolean,
    val vatLines: List<InvoiceVatLineDto>,
    val withholdings: String,
    val total: String?,
    val quarterClosed: Boolean,
    val recognition: InvoiceRecognitionSummaryDto?,
    val notices: List<InvoiceNoticeDto>
) {
    override fun toString(): String = "InvoiceDto(id=$id, status=$status)"
}

fun Invoice.toDto(quarterClosed: Boolean, recognition: InvoiceRecognitionSummaryDto?, notices: List<InvoiceNotice>) = InvoiceDto(
    id = id,
    status = status,
    type = type,
    version = version,
    issuer = issuer?.let { InvoicePartyDto(it.name, it.nif) },
    recipient = recipient?.let { InvoicePartyDto(it.name, it.nif) },
    number = number,
    issueDate = issueDate?.toString(),
    concept = concept,
    currency = currency,
    corrective = corrective,
    vatLines = vatLines.map {
        InvoiceVatLineDto(it.vatRate.toPlainString(), it.base.toPlainString(), it.vatAmount.toPlainString(),
            it.surcharge.toPlainString(), it.noVatCause?.name)
    },
    withholdings = withholdings.toPlainString(),
    total = total?.toPlainString(),
    quarterClosed = quarterClosed,
    recognition = recognition,
    notices = notices.map { InvoiceNoticeDto(it.field, it.code.name, it.blocking, it.message) }
)

private const val DECIMAL = "^-?\\d{1,10}(\\.\\d{1,2})?$"

data class InvoiceVatLineRequest(
    @field:jakarta.validation.constraints.Pattern(regexp = DECIMAL) val vatRate: String,
    @field:jakarta.validation.constraints.Pattern(regexp = DECIMAL) val base: String,
    @field:jakarta.validation.constraints.Pattern(regexp = DECIMAL) val vatAmount: String,
    @field:jakarta.validation.constraints.Pattern(regexp = DECIMAL) val surcharge: String = "0.00",
    val noVatCause: com.granatum.core.domain.model.NoVatCause? = null
) {
    override fun toString(): String = "InvoiceVatLineRequest(***)"
}

/** `PUT …/invoices/{id}`: every editable field, the breakdown whole, and the version read. */
data class InvoiceRequest(
    val type: InvoiceType? = null,
    @field:jakarta.validation.Valid val issuer: InvoicePartyDto? = null,
    @field:jakarta.validation.Valid val recipient: InvoicePartyDto? = null,
    @field:jakarta.validation.constraints.Size(max = 60) val number: String? = null,
    val issueDate: java.time.LocalDate? = null,
    @field:jakarta.validation.constraints.Size(max = 500) val concept: String? = null,
    @field:jakarta.validation.constraints.Pattern(regexp = "^[A-Z]{3}$") val currency: String = "EUR",
    val corrective: Boolean = false,
    @field:jakarta.validation.Valid val vatLines: List<InvoiceVatLineRequest> = emptyList(),
    @field:jakarta.validation.constraints.Pattern(regexp = DECIMAL) val withholdings: String = "0.00",
    @field:jakarta.validation.constraints.Pattern(regexp = DECIMAL) val total: String? = null,
    val version: Int
) {
    override fun toString(): String = "InvoiceRequest(version=$version)"
}

data class InvoiceVersionRequest(val version: Int)
