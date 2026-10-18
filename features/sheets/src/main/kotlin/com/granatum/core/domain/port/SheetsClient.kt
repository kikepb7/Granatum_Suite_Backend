package com.granatum.core.domain.port

import java.math.BigDecimal
import java.util.UUID

/**
 * One cell to write. Every write uses `valueInputOption=RAW` (research.md
 * D-004), so a [Text] is stored exactly as given - a concept starting with `=`
 * stays text, never a formula - and a [Number] stays a number the sheet can sum.
 */
sealed interface SheetCell {
    data class Text(val value: String) : SheetCell {
        override fun toString(): String = "Text(***)"
    }

    data class Number(val value: BigDecimal) : SheetCell {
        override fun toString(): String = "Number(***)"
    }

    companion object {
        fun text(value: String?): SheetCell = Text(value ?: "")
        fun number(value: BigDecimal): SheetCell = Number(value)
    }
}

/**
 * The spreadsheets as this module needs them (research.md D-004, D-007). The
 * real implementation is `GoogleSheetsClient`; without credentials it is
 * [DisabledSheetsClient], and the tests use a fake.
 *
 * Every call goes to the network: **never call it inside a database
 * transaction** (research.md D-003). Each call either returns or throws one of
 * [SheetsPermanentException], [SheetsTransientException] or
 * [SheetsUnavailableException].
 *
 * A tab is named `YYYY-MM`. Rewriting it touches no other tab.
 */
interface SheetsClient {

    /** False for [DisabledSheetsClient]: the server has no Google credentials. */
    val active: Boolean

    /** The service account the ADMIN must share the spreadsheets with, read from its credentials. */
    fun serviceAccountEmail(): String

    /**
     * Checks that the spreadsheet exists, is visible and can be **edited**,
     * by rewriting its own title unchanged. Throws [SheetsPermanentException]
     * with `NOT_FOUND`, `NOT_SHARED` or `READ_ONLY`.
     */
    fun checkAccess(spreadsheetId: String)

    /**
     * Replaces the whole content of [tab] with [header] and [rows], creating
     * the tab if missing. With [hiddenIdColumn], the first cell of each row is
     * an invoice id and column A is hidden and protected when the tab is
     * created (the receipts' layout).
     */
    fun rewriteTab(
        spreadsheetId: String,
        tab: String,
        header: List<String>,
        rows: List<List<SheetCell>>,
        hiddenIdColumn: Boolean = false
    )

    /**
     * Writes one invoice in a receipts tab: finds [invoiceId] in column A and
     * updates that row, or appends one if absent. [row] is the cells after the
     * id; [header] (id column included) is written only when the tab is created
     * or empty.
     */
    fun upsertInvoiceRow(spreadsheetId: String, tab: String, header: List<String>, invoiceId: UUID, row: List<SheetCell>)

    /** Deletes the row of [invoiceId] from [tab]. Nothing to do if the tab or the row is missing. */
    fun removeInvoiceRow(spreadsheetId: String, tab: String, invoiceId: UUID)
}

/** Why Google refused for good: retrying would get the same answer. */
enum class SheetsFailureReason(val message: String) {
    NOT_FOUND("La hoja no existe o la cuenta técnica de Google no la ve"),
    NOT_SHARED("La hoja no está compartida con la cuenta técnica de Google"),
    READ_ONLY("La hoja está compartida con la cuenta técnica de Google solo como lectora"),
    BAD_REQUEST("Google ha rechazado la petición de escritura en la hoja")
}

/** Not retried: 400, 403, 404 (research.md D-003). The message is Spanish and quotes no content. */
class SheetsPermanentException(
    val reason: SheetsFailureReason,
    message: String = reason.message
) : RuntimeException(message)

/** Retried with growing waits: 429, 5xx, timeouts, network, token refresh. */
class SheetsTransientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The server has no Google credentials: the integration is off (FR-005). */
class SheetsUnavailableException :
    RuntimeException("La copia en Google Sheets no está disponible: el servidor no tiene credenciales de Google")

/** The client when `sheets.google.credentials-file` is empty: every call refuses. */
class DisabledSheetsClient : SheetsClient {
    override val active: Boolean = false
    override fun serviceAccountEmail(): String = throw SheetsUnavailableException()
    override fun checkAccess(spreadsheetId: String) = throw SheetsUnavailableException()
    override fun rewriteTab(
        spreadsheetId: String,
        tab: String,
        header: List<String>,
        rows: List<List<SheetCell>>,
        hiddenIdColumn: Boolean
    ) = throw SheetsUnavailableException()
    override fun upsertInvoiceRow(spreadsheetId: String, tab: String, header: List<String>, invoiceId: UUID, row: List<SheetCell>) =
        throw SheetsUnavailableException()
    override fun removeInvoiceRow(spreadsheetId: String, tab: String, invoiceId: UUID) = throw SheetsUnavailableException()
    override fun toString(): String = "DisabledSheetsClient"
}
