package com.granatum.core.service

import com.granatum.core.domain.contract.ShiftExportRecorder
import com.granatum.core.domain.contract.ShiftSheetRow
import com.granatum.core.domain.contract.ShiftSheetSource
import com.granatum.core.domain.model.SheetTask
import com.granatum.core.domain.model.SheetTaskKind
import com.granatum.core.domain.port.SheetCell
import com.granatum.core.domain.port.SheetsClient
import com.granatum.core.domain.type.Role
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.HexFormat

/**
 * Writes a month of shifts (`SHIFT_MONTH`, reference `YYYY-MM`; US2,
 * research.md D-004): reads the month through [ShiftSheetSource], rewrites the
 * whole tab `YYYY-MM` of the shifts spreadsheet - so exporting twice gives the
 * same rows (SC-002) - and records the export in `exports` through
 * [ShiftExportRecorder], with the ADMIN who asked (`requested_by`) and the
 * SHA-256 of the rows written (FR-012).
 *
 * Runs outside any transaction, as every [SheetTaskHandler]: the read opens its
 * own snapshot, and the record its own short transaction, after the write.
 * If the record fails, the task is retried and the tab rewritten identically.
 */
@Component
class ShiftMonthWriter(
    private val source: ShiftSheetSource,
    private val recorder: ShiftExportRecorder,
    private val settings: SheetSettingsService,
    private val client: SheetsClient
) : SheetTaskHandler {

    companion object {
        /** The columns of a shifts tab (data-model.md). */
        val HEADER = listOf("Nombre", "Apellidos", "DNI", "Fecha", "Entrada", "Salida")

        private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")

        /** One row as text cells: dates `dd/MM/yyyy`, times `HH:mm`, empty for a missing last name or clock-out. */
        fun cells(row: ShiftSheetRow): List<String> = listOf(
            row.name,
            row.lastName.orEmpty(),
            row.identityDocument,
            row.date.format(DATE),
            row.clockIn.format(TIME),
            row.clockOut?.format(TIME).orEmpty()
        )

        /**
         * The fingerprint recorded in `exports.fingerprint`: SHA-256, in hex, of
         * the tab as written - the header and then each row - with each row's
         * cells joined by a tab character and ended by `\n`, in UTF-8. The same
         * data always gives the same fingerprint.
         */
        fun fingerprint(rows: List<List<String>>): String {
            val canonical = (listOf(HEADER) + rows).joinToString("") { it.joinToString("\t") + "\n" }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
        }
    }

    override val kind = SheetTaskKind.SHIFT_MONTH

    override fun handle(task: SheetTask): SheetTaskResult {
        val month = YearMonth.parse(task.reference)
        val spreadsheetId = settings.find()?.shiftsSpreadsheetId
            ?: throw SheetTaskAbortedException("La hoja de fichajes no está configurada")
        val requester = task.requestedBy
            ?: throw SheetTaskAbortedException("La exportación no indica qué persona la pidió")

        val data = source.month(month)
        val rows = data.rows.map(::cells)
        client.rewriteTab(spreadsheetId, month.toString(), HEADER, rows.map { r -> r.map { SheetCell.text(it) } })

        // Only the ADMIN reaches /api/sheets (SecurityConfig), so that is the role.
        recorder.recordSheetExport(requester, Role.ADMIN, month, rows.size, fingerprint(rows))
        return SheetTaskResult(rowCount = rows.size, lastNamesPending = data.lastNamesPending)
    }
}
