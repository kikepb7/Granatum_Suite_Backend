package com.granatum.core.domain.model

import java.time.Instant
import java.util.UUID

/** What a queued write copies (data-model.md, `sheet_tasks.kind`). */
enum class SheetTaskKind {
    /** A month of shifts; reference `YYYY-MM`. */
    SHIFT_MONTH,

    /** One invoice; reference its id. */
    INVOICE,

    /** A month of receipts, rewritten whole; reference `YYYY-MM`. */
    INVOICE_MONTH
}

enum class SheetTaskStatus { PENDING, RUNNING, DONE, FAILED }

/**
 * Why the last attempt of a task failed, for the app to act on
 * (`sheet_tasks.error_reason`, V38); null when it did not fail.
 */
enum class SheetTaskErrorReason {
    /** Google refused for good: see [com.granatum.core.domain.port.SheetsFailureReason]. */
    NOT_FOUND, NOT_SHARED, READ_ONLY, BAD_REQUEST, API_DISABLED,

    /** The server has no Google credentials. */
    UNAVAILABLE,

    /** No writer for the task's kind. */
    NO_HANDLER,

    /** The writer gave up, e.g. the spreadsheet is no longer configured. */
    ABORTED,

    /** The last attempt failed transiently: retried later, or out of attempts. */
    TRANSIENT;

    companion object {
        fun of(reason: com.granatum.core.domain.port.SheetsFailureReason): SheetTaskErrorReason = valueOf(reason.name)
    }
}

/** A queued write, as the worker hands it to its [com.granatum.core.service.SheetTaskHandler]. */
data class SheetTask(
    val id: UUID,
    val kind: SheetTaskKind,
    val reference: String,
    val status: SheetTaskStatus,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val lastError: String?,
    val errorReason: SheetTaskErrorReason?,
    val requestedBy: UUID?,
    val rowCount: Int?,
    val lastNamesPending: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?
)

/** The queue at a glance (FR-023). */
data class SheetQueueStatus(
    val lastSuccessAt: Instant?,
    val pending: Long,
    val failed: Long,
    val recent: List<SheetTask>
)

/** The configured spreadsheets (data-model.md, `sheet_settings`). */
data class SheetSettings(
    val shiftsSpreadsheetId: String?,
    val invoicesSpreadsheetId: String?,
    val configuredBy: UUID,
    val configuredAt: Instant
)
