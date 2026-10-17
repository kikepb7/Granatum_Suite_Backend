package com.granatum.core.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

// Invoicing domain (feature 004). No JPA, no HTTP.
//
// Every type that carries names, tax ids or amounts overrides toString():
// Spring MVC logs objects through it at DEBUG, which is how feature 002 leaked
// passwords, and an invoice is a list of exactly those things (FR-027).

enum class InvoiceStatus { PENDING_RECOGNITION, DRAFT, CONFIRMED, DISCARDED }

enum class InvoiceType { ISSUED, RECEIVED }

/**
 * Why a VAT line carries no tax although the operation exists (research.md
 * D-020). Without a cause, a 0% line looks like a misread one.
 */
enum class NoVatCause { EXEMPT, REVERSE_CHARGE, INTRA_COMMUNITY }

/** Issuer or recipient. Both fields optional: a simplified invoice has no recipient. */
data class InvoiceParty(val name: String?, val nif: String?) {
    override fun toString(): String = "InvoiceParty(***)"
}

/** One VAT rate of an invoice, with its base, tax and equivalence surcharge. */
data class VatLine(
    val vatRate: BigDecimal,
    val base: BigDecimal,
    val vatAmount: BigDecimal,
    val surcharge: BigDecimal = BigDecimal.ZERO,
    val noVatCause: NoVatCause? = null
) {
    override fun toString(): String = "VatLine(vatRate=$vatRate)"
}

data class Invoice(
    val id: UUID,
    val status: InvoiceStatus,
    val type: InvoiceType?,
    val issuer: InvoiceParty?,
    val recipient: InvoiceParty?,
    val number: String?,
    val issueDate: LocalDate?,
    val concept: String?,
    val currency: String,
    val vatLines: List<VatLine>,
    val withholdings: BigDecimal,
    val total: BigDecimal?,
    val corrective: Boolean,
    val version: Int
) {
    override fun toString(): String = "Invoice(id=$id, status=$status)"
}

enum class InvoiceNoticeCode {
    REQUIRED, TOTAL_MISMATCH, INVALID_NIF, UNCERTAIN, DUPLICATE, CURRENCY, FUTURE_DATE,
    OLD_DATE, NOT_COMPANY_INVOICE, MISSING_NO_VAT_CAUSE, NOT_AN_INVOICE, NEGATIVE_AMOUNT,
    MULTIPLE_INVOICES, QUARTER_CLOSED
}

/**
 * Something about an invoice worth looking at before confirming it. Part of the
 * resource, not an error (contracts/README.md): [blocking] says whether it
 * prevents confirmation. The message never quotes a name or a tax id.
 */
data class InvoiceNotice(
    val field: String,
    val code: InvoiceNoticeCode,
    val blocking: Boolean,
    val message: String
)

/**
 * What the recognition proposed, exactly as it came back (FR-005). Every field
 * nullable: what could not be read stays empty instead of being invented
 * (FR-004). Amounts as decimal strings, so no cent is lost in transit
 * (research.md D-003).
 */
data class RecognizedProposal(
    val isInvoice: Boolean,
    val multipleInvoices: Boolean,
    val issuerName: String?,
    val issuerNif: String?,
    val recipientName: String?,
    val recipientNif: String?,
    val number: String?,
    val issueDate: String?,
    val concept: String?,
    val currency: String?,
    val vatLines: List<ProposedVatLine>,
    val withholdings: String?,
    val total: String?,
    val corrective: Boolean?,
    val uncertainFields: List<String>
) {
    override fun toString(): String = "RecognizedProposal(isInvoice=$isInvoice, vatLines=${vatLines.size})"
}

data class ProposedVatLine(
    val vatRate: String?,
    val base: String?,
    val vatAmount: String?,
    val surcharge: String?,
    val noVatCause: String?
) {
    override fun toString(): String = "ProposedVatLine(***)"
}

/** A reporting period. Invoices belong to the period of their issue date (FR-018). */
sealed interface ReportPeriod {
    val year: Int
    val from: LocalDate
    val to: LocalDate
    /** For file names: `2026-10`, `2026-T3`, `2026`. */
    val label: String

    data class Monthly(override val year: Int, val month: Int) : ReportPeriod {
        override val from: LocalDate get() = YearMonth.of(year, month).atDay(1)
        override val to: LocalDate get() = YearMonth.of(year, month).atEndOfMonth()
        override val label: String get() = "$year-${month.toString().padStart(2, '0')}"
    }

    data class Quarterly(override val year: Int, val quarter: Int) : ReportPeriod {
        override val from: LocalDate get() = LocalDate.of(year, (quarter - 1) * 3 + 1, 1)
        override val to: LocalDate get() = YearMonth.of(year, quarter * 3).atEndOfMonth()
        override val label: String get() = "$year-T$quarter"
    }

    data class Yearly(override val year: Int) : ReportPeriod {
        override val from: LocalDate get() = LocalDate.of(year, 1, 1)
        override val to: LocalDate get() = LocalDate.of(year, 12, 31)
        override val label: String get() = "$year"
    }

    companion object {
        /** The fiscal quarter (1-4) a date falls in. */
        fun quarterOf(date: LocalDate): Int = (date.monthValue - 1) / 3 + 1
    }
}

/** Totals of one group (issued or received) in a period (FR-019). */
data class GroupTotals(
    val invoices: Int,
    val base: BigDecimal,
    /** VAT per rate, ordered by rate. */
    val vatByRate: Map<BigDecimal, BigDecimal>,
    val surcharge: BigDecimal,
    val withholdings: BigDecimal,
    val total: BigDecimal,
    /** Base of the lines carrying no VAT, by cause (research.md D-020). */
    val noVatBase: Map<NoVatCause, BigDecimal>
) {
    override fun toString(): String = "GroupTotals(invoices=$invoices)"

    companion object {
        val EMPTY = GroupTotals(0, BigDecimal.ZERO.setScale(2), emptyMap(), BigDecimal.ZERO.setScale(2),
            BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), emptyMap())
    }
}

data class ClosedQuarter(val year: Int, val quarter: Int, val from: Instant)

data class InvoicingReport(
    val period: ReportPeriod,
    val issued: GroupTotals,
    val received: GroupTotals,
    /** Invoices of the period not yet confirmed: they do not count, and the report says so (FR-021). */
    val pending: Int,
    val closedQuarters: List<ClosedQuarter>,
    val calculatedAt: Instant
) {
    override fun toString(): String = "InvoicingReport(period=${period.label})"
}
