package com.granatum.core.domain.contract

import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.Role
import java.time.YearMonth

/**
 * Records an export of the register to the Google spreadsheet in the existing
 * export log (feature 012, FR-012, research.md D-006): who sent the identity
 * documents to Google, when, which period and a fingerprint of the rows.
 *
 * `timetracking` implements it - it owns `exports`, which stays INSERT-only -
 * and `sheets` calls it once the tab has been written. Implementations run in
 * their own short transaction; never call it inside another one.
 */
interface ShiftExportRecorder {

    /**
     * Inserts one completed row with `destination = GOOGLE_SHEETS`, the whole
     * [month] as the period, [rowCount] data rows and [fingerprint], the
     * SHA-256 in hex of the rows written (64 characters).
     */
    fun recordSheetExport(
        requesterId: EntityId,
        requesterRole: Role,
        month: YearMonth,
        rowCount: Int,
        fingerprint: String
    )
}
