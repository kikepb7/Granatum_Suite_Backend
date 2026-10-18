package com.granatum.core.infrastructure.contract

import com.granatum.core.domain.contract.ShiftExportRecorder
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.ExportDestination
import com.granatum.core.domain.type.ExportScope
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.entities.RegisterExportEntity
import com.granatum.core.infrastructure.database.repositories.RegisterExportRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.YearMonth

/**
 * This module's side of the `ShiftExportRecorder` contract (feature 012,
 * FR-012, research.md D-006): one INSERT in `exports`, which stays
 * INSERT-only.
 *
 * The whole staff is recorded as `STAFF` with no employee: `ck_exports_employee`
 * (V29) only lets `STAFF` have a null employee, and `MONTHLY` means one
 * person's monthly download. The period is the whole month, and the row is
 * always complete: it is recorded once the tab has been written.
 *
 * `Propagation.NEVER`, like `RegisterExportLog.record`: the caller writes to
 * Google outside any transaction and then records; a call inside one would be
 * a mistake worth failing loudly.
 */
@Component
class ShiftExportRecorderJpa(
    private val exports: RegisterExportRepository,
    private val clock: Clock = Clock.systemUTC()
) : ShiftExportRecorder {

    @Transactional(propagation = Propagation.NEVER)
    override fun recordSheetExport(
        requesterId: EntityId,
        requesterRole: Role,
        month: YearMonth,
        rowCount: Int,
        fingerprint: String
    ) {
        exports.save(
            RegisterExportEntity(
                requesterId = requesterId,
                requesterRole = requesterRole,
                scope = ExportScope.STAFF,
                employeeId = null,
                from = month.atDay(1),
                to = month.atEndOfMonth(),
                generatedAt = clock.instant(),
                completed = true,
                rowCount = rowCount,
                fingerprint = fingerprint,
                destination = ExportDestination.GOOGLE_SHEETS
            )
        )
    }
}
