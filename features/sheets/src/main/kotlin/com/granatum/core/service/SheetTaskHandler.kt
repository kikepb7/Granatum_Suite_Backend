package com.granatum.core.service

import com.granatum.core.domain.model.SheetTask
import com.granatum.core.domain.model.SheetTaskKind

/**
 * Writes one kind of task (research.md D-003, D-004). Each is a Spring bean;
 * [com.granatum.core.scheduling.SheetTaskWorker] collects them all and
 * dispatches by [kind]. At most one handler per kind: two fail the startup.
 *
 * [handle] runs **outside any transaction** and may take seconds: it reads
 * what it needs (each read in its own short transaction), calls the
 * [com.granatum.core.domain.port.SheetsClient], and returns. To end the task it
 * either returns (DONE) or throws:
 * - [com.granatum.core.domain.port.SheetsTransientException]: retried later;
 * - [com.granatum.core.domain.port.SheetsPermanentException],
 *   [com.granatum.core.domain.port.SheetsUnavailableException] or
 *   [SheetTaskAbortedException]: FAILED at once, with the message as the reason.
 *
 * Any other exception counts as transient: a bug must not lose the write, and
 * after `sheets.max-attempts` it shows as FAILED anyway.
 */
interface SheetTaskHandler {
    val kind: SheetTaskKind
    fun handle(task: SheetTask): SheetTaskResult
}

/** What the task reports when it ends well (`sheet_tasks.row_count`, `last_names_pending`). */
data class SheetTaskResult(val rowCount: Int? = null, val lastNamesPending: Int? = null)

/**
 * A handler gives up for good with a readable Spanish [message] - for
 * example, the spreadsheet is no longer configured. Never quote personal data
 * or sheet content: it lands in `sheet_tasks.last_error` and in the status.
 */
class SheetTaskAbortedException(message: String) : RuntimeException(message)
