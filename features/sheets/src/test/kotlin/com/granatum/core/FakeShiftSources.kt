package com.granatum.core

import com.granatum.core.domain.contract.ShiftExportRecorder
import com.granatum.core.domain.contract.ShiftSheetMonth
import com.granatum.core.domain.contract.ShiftSheetSource
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.Role
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stands in for `timetracking`'s side of the `ShiftSheetSource` contract,
 * which this module never sees (principle I). A plain bean, picked up by the
 * test application's component scan, because the real writer needs one in
 * every context of the suite. Tests script it per month.
 */
@Component
class FakeShiftSheetSource : ShiftSheetSource {
    val months = ConcurrentHashMap<YearMonth, ShiftSheetMonth>()
    @Volatile var cutoff: LocalDate = LocalDate.parse("2022-10-18")

    override fun month(month: YearMonth): ShiftSheetMonth = months[month] ?: ShiftSheetMonth(emptyList(), 0)
    override fun retentionCutoff(): LocalDate = cutoff

    fun reset() {
        months.clear()
        cutoff = LocalDate.parse("2022-10-18")
    }
}

/** Records what `ShiftExportRecorder` would insert in `exports`, and whether a transaction was open. */
@Component
class FakeShiftExportRecorder : ShiftExportRecorder {
    data class Recorded(
        val requesterId: EntityId,
        val requesterRole: Role,
        val month: YearMonth,
        val rowCount: Int,
        val fingerprint: String,
        val inTransaction: Boolean
    )

    val recorded = CopyOnWriteArrayList<Recorded>()

    override fun recordSheetExport(requesterId: EntityId, requesterRole: Role, month: YearMonth, rowCount: Int, fingerprint: String) {
        recorded += Recorded(
            requesterId, requesterRole, month, rowCount, fingerprint,
            TransactionSynchronizationManager.isActualTransactionActive()
        )
    }

    fun reset() = recorded.clear()
}
