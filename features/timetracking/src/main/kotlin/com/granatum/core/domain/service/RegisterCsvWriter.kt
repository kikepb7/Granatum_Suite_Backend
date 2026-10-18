package com.granatum.core.domain.service

import com.granatum.core.api.util.CivilDateRange
import com.granatum.core.csv.CsvFormat
import com.granatum.core.domain.model.RegisterRow
import com.granatum.core.domain.model.BreakSpan
import com.granatum.core.domain.type.BreakType
import com.granatum.core.domain.type.ContractType
import com.granatum.core.domain.type.ShiftStatus
import java.io.OutputStream
import java.time.format.DateTimeFormatter

/**
 * Writes the exported register as CSV, one line at a time, so the caller can
 * stream it in batches and never hold the whole file (D-002).
 *
 * ## Format (D-004)
 *
 * - UTF-8 **with BOM**: without it, Excel on Windows reads the file as ANSI and
 *   mangles accents (FR-008).
 * - `;` as separator: a Spanish-locale spreadsheet uses `,` as the decimal mark
 *   and `;` as the list separator, so `,` would put everything in column A.
 * - CRLF, and RFC 4180 quoting: a value with `;`, `"`, CR or LF goes between
 *   double quotes with inner quotes doubled, so every row keeps the same number
 *   of columns (FR-010).
 * - Dates `yyyy-MM-dd`, entry and exit `yyyy-MM-dd HH:mm` (a shift that crosses
 *   midnight would lie with the time alone), hours `H:MM` plus whole minutes for
 *   whoever processes the file with a program. All in `Europe/Madrid` (FR-003).
 *
 * ## No cell runs as a formula (D-005)
 *
 * A cell starting with `=`, `+`, `-`, `@`, tab or CR is written with a leading
 * apostrophe and quoted: the OWASP mitigation for CSV injection. These files go
 * to third parties - the Labour Inspectorate, the workers' representatives - and
 * a name starting with `=` would otherwise execute when opened (FR-009).
 *
 * The guard has no false positives because **no legitimate value starts with
 * one of those characters**: there are no negative numbers, dates and times
 * start with a digit, enum names with a letter. Only free text - names, job
 * titles - can trigger it, which is exactly where the risk is.
 *
 * Written by hand rather than with a CSV library: none applies the formula
 * guard by default, and the rules above are the whole of the format.
 *
 * Pure: knows nothing of JPA or HTTP, holds no state, and the same rows always
 * produce the same bytes (FR-011).
 */
object RegisterCsvWriter {

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(CivilDateRange.ZONE)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(CivilDateRange.ZONE)

    private const val DOCUMENT_COLUMN = "Documento"

    private val COLUMNS = listOf(
        "Nombre", "Apellidos", DOCUMENT_COLUMN, "Puesto", "Fecha", "Entrada", "Salida", "Pausas",
        "Horas trabajadas", "Minutos trabajados", "Estado", "Completado a posteriori",
        "Corregido", "Entrada original", "Salida original", "Pausas originales", "Correcciones"
    )

    /**
     * The column is **omitted**, not left blank, when the requester may not see
     * the document: a "Documento" header over empty cells suggests the data
     * exists and is missing (D-012).
     */
    private fun columns(includeDocument: Boolean) =
        if (includeDocument) COLUMNS else COLUMNS - DOCUMENT_COLUMN

    fun writeHeader(out: OutputStream, includeDocument: Boolean) {
        CsvFormat.writeBom(out)
        writeLine(out, columns(includeDocument))
    }

    fun writeRow(out: OutputStream, row: RegisterRow, includeDocument: Boolean) {
        val cells = buildList {
            add(row.name)
            add(row.lastName.orEmpty())
            if (includeDocument) add(row.document.orEmpty())
            add(row.position)
            add(DATE.format(row.date))
            add(DATE_TIME.format(row.clockIn))
            add(row.clockOut?.let(DATE_TIME::format).orEmpty())
            add(breaks(row.breaks))
            add(row.workedMinutes?.let(::hours).orEmpty())
            add(row.workedMinutes?.toString().orEmpty())
            add(label(row.status))
            add(yesNo(row.completedAfterwards))
            add(yesNo(row.corrected))
            add(row.original?.clockIn?.let(DATE_TIME::format).orEmpty())
            add(row.original?.clockOut?.let(DATE_TIME::format).orEmpty())
            add(row.original?.breaks?.let(::breaks).orEmpty())
            add(
                row.corrections.joinToString(" | ") {
                    "${DATE_TIME.format(it.resolvedAt)} solicitada por ${it.requester}, " +
                        "aprobada por ${it.approver}"
                }
            )
        }
        writeLine(out, cells)
    }

    /**
     * The closing block of the monthly download: a blank line, the total, the
     * contract type and whether the month is closed (D-011).
     *
     * The total goes under "Horas trabajadas" and "Minutos trabajados" wherever
     * those columns fall: without the document column they move one place left,
     * so the position comes from the header rather than a fixed count of
     * separators. Every block row is as wide as the header (FR-010).
     */
    fun writeMonthlyBlock(
        out: OutputStream,
        totalMinutes: Int,
        contractType: ContractType,
        monthClosed: Boolean,
        includeDocument: Boolean
    ) {
        val columns = columns(includeDocument)
        fun row(vararg values: Pair<Int, String>): List<String> =
            MutableList(columns.size) { "" }.also { cells -> values.forEach { (i, v) -> cells[i] = v } }

        CsvFormat.writeEmptyLine(out)
        writeLine(
            out,
            row(
                0 to "Total del mes",
                columns.indexOf("Horas trabajadas") to hours(totalMinutes),
                columns.indexOf("Minutos trabajados") to totalMinutes.toString()
            )
        )
        writeLine(out, row(0 to "Tipo de contrato", 1 to label(contractType)))
        writeLine(out, row(0 to "Mes cerrado", 1 to yesNo(monthClosed)))
    }

    /**
     * How a person is named inside a cell that holds a whole person, such as
     * the requester and approver in `Correcciones` (feature 012): name and last
     * name, or the bare name while the last name is pending - that name is
     * already the whole one the person was registered with (FR-003).
     */
    fun fullName(name: String, lastName: String?): String =
        if (lastName.isNullOrBlank()) name else "$name $lastName"

    // Cell rules (BOM, quoting, formula guard) live in common's CsvFormat,
    // shared with the invoice reports of feature 004.
    private fun writeLine(out: OutputStream, cells: List<String>) =
        CsvFormat.writeTextLine(out, cells)

    /** `H:MM`, never padded on the hours: `0:05`, `8:30`, `26:30`. */
    private fun hours(minutes: Int): String = "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"

    private fun yesNo(value: Boolean) = if (value) "Sí" else "No"

    /** `COMIDA 11:00-11:30`, comma-separated; an open break ends in `-`. */
    private fun breaks(breaks: List<BreakSpan>): String =
        breaks.joinToString(", ") { p ->
            "${label(p.type)} ${TIME.format(p.start)}-${p.end?.let(TIME::format).orEmpty()}"
        }

    // The file is read by people - the Labour Inspectorate, the workers'
    // representatives, the person themselves - so its cells keep the Spanish
    // wording it always had. The enum names are API and database codes, which
    // have been English since V29; the file is not a wire format and does not
    // follow them.

    /** The shift's state as the file shows it. */
    fun label(status: ShiftStatus): String = when (status) {
        ShiftStatus.IN_PROGRESS -> "EN_CURSO"
        ShiftStatus.CLOSED -> "CERRADO"
        ShiftStatus.INCOMPLETE -> "INCOMPLETO"
    }

    /** The kind of break as the file shows it. */
    fun label(type: BreakType): String = when (type) {
        BreakType.MEAL -> "COMIDA"
        BreakType.REST -> "DESCANSO"
        BreakType.OTHER -> "OTRO"
    }

    /** The contract type as the file shows it. */
    fun label(type: ContractType): String = when (type) {
        ContractType.FULL_TIME -> "JORNADA_COMPLETA"
        ContractType.PART_TIME -> "PARCIAL"
        ContractType.HOURLY -> "POR_HORAS"
    }
}
