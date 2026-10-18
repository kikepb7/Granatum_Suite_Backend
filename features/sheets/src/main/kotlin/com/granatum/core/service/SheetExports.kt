package com.granatum.core.service

import com.granatum.core.domain.contract.ShiftSheetSource
import com.granatum.core.domain.exception.InvalidSheetPeriodException
import com.granatum.core.domain.exception.SheetsNotConfiguredException
import com.granatum.core.domain.exception.SpreadsheetRole
import com.granatum.core.domain.model.SheetTaskKind
import com.granatum.core.domain.port.SheetsClient
import com.granatum.core.domain.port.SheetsUnavailableException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.DateTimeException
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * The writes an ADMIN asks for (contracts/README.md): a month of shifts
 * (`POST /api/sheets/shifts/{year}/{month}`) and the resync of a month of
 * receipts (`POST /api/sheets/invoices/{year}/{month}/resync`).
 *
 * Validates, then queues the task - joining a live one of the same month - in
 * its own short transaction, and returns its id. The write happens in the
 * worker; the caller kicks it once this returns, so the task is committed.
 */
@Service
class SheetExports(
    private val queue: SheetTaskQueue,
    private val settings: SheetSettingsService,
    private val client: SheetsClient,
    private val shifts: ShiftSheetSource,
    private val clock: Clock = Clock.systemUTC()
) {
    private val madrid = ZoneId.of("Europe/Madrid")

    /** `400 INVALID_PERIOD` for a month that does not exist, is in the future or before the retention period. */
    fun exportShifts(year: Int, month: Int, requester: UUID): UUID {
        val period = period(year, month)
        if (period.atEndOfMonth().isBefore(shifts.retentionCutoff())) {
            throw InvalidSheetPeriodException("$period es anterior al plazo de conservación del registro")
        }
        if (!client.active) throw SheetsUnavailableException()
        settings.find()?.shiftsSpreadsheetId ?: throw SheetsNotConfiguredException(SpreadsheetRole.SHIFTS)
        return queue.enqueue(SheetTaskKind.SHIFT_MONTH, period.toString(), requester)
    }

    /** Rewrites the month's receipts tab with every confirmed invoice of the month (FR-018). */
    fun resyncInvoices(year: Int, month: Int, requester: UUID): UUID {
        val period = period(year, month)
        if (!client.active) throw SheetsUnavailableException()
        settings.find()?.invoicesSpreadsheetId ?: throw SheetsNotConfiguredException(SpreadsheetRole.INVOICES)
        return queue.enqueue(SheetTaskKind.INVOICE_MONTH, period.toString(), requester)
    }

    private fun period(year: Int, month: Int): YearMonth {
        val period = try {
            if (year < 1) throw DateTimeException("year")
            YearMonth.of(year, month)
        } catch (e: DateTimeException) {
            throw InvalidSheetPeriodException("el año y el mes no forman un mes válido")
        }
        if (period.isAfter(YearMonth.now(clock.withZone(madrid)))) {
            throw InvalidSheetPeriodException("$period es posterior al mes actual")
        }
        return period
    }
}
