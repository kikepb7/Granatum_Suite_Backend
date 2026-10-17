package com.granatum.core.csv

import java.io.OutputStream

/**
 * The CSV every feature hands out to a spreadsheet: the register export
 * (feature 003) and the invoice reports (feature 004). Moved here from
 * timetracking's `EscritorCsv` so both share one implementation of the rules
 * below instead of two that would drift apart (constitution principle I:
 * what features share lives in common).
 *
 * - UTF-8 **with BOM**: without it Excel on Windows reads the file as ANSI and
 *   mangles accents.
 * - `;` as separator and CRLF: a Spanish-locale spreadsheet uses `,` as the
 *   decimal mark, so `,` would put everything in one column.
 * - RFC 4180 quoting: a value with `;`, `"`, CR or LF is quoted, inner quotes
 *   doubled, so every row keeps its column count.
 * - **No text cell runs as a formula**: one starting with `=`, `+`, `-`, `@`,
 *   tab or CR gets a leading apostrophe and is quoted (the OWASP mitigation for
 *   CSV injection). These files go to third parties.
 *
 * ## Numeric cells
 *
 * The register export never had negative numbers, but an invoice report does:
 * credit notes subtract. Written as text, `-150,00` would get the apostrophe
 * and the spreadsheet would read it as text, breaking every sum. A [Number]
 * accepts only a plain decimal with a comma - which no spreadsheet runs as a
 * formula - and is written as is; anything else is refused rather than written,
 * so the formula guard cannot be bypassed by calling a value a number.
 */
object CsvFormat {

    sealed interface Cell

    /** Free or formatted text: protected against formulas and quoted as needed. */
    @JvmInline
    value class Text(val value: String) : Cell

    /** An amount with a comma decimal mark, e.g. `1764,00` or `-150,00`. */
    @JvmInline
    value class Number(val value: String) : Cell

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private const val SEPARATOR = ";"
    private const val LINE_END = "\r\n"
    private val DANGEROUS_STARTS = setOf('=', '+', '-', '@', '\t', '\r')
    private val DECIMAL = Regex("""-?\d+(,\d{1,2})?""")

    fun text(value: String): Cell = Text(value)

    /** @throws IllegalArgumentException if [value] is not a plain decimal amount. */
    fun number(value: String): Cell {
        require(DECIMAL.matches(value)) { "no es un importe decimal simple" }
        return Number(value)
    }

    fun writeBom(out: OutputStream) = out.write(BOM)

    fun writeLine(out: OutputStream, cells: List<Cell>) {
        out.write((cells.joinToString(SEPARATOR, transform = ::format) + LINE_END).toByteArray(Charsets.UTF_8))
    }

    /** A row of text cells, the common case. */
    fun writeTextLine(out: OutputStream, values: List<String>) = writeLine(out, values.map(::text))

    fun writeEmptyLine(out: OutputStream) = out.write(LINE_END.toByteArray(Charsets.UTF_8))

    private fun format(cell: Cell): String = when (cell) {
        is Number -> cell.value
        is Text -> {
            val value = cell.value
            val dangerous = value.isNotEmpty() && value[0] in DANGEROUS_STARTS
            val text = if (dangerous) "'$value" else value
            val quoted = dangerous || text.any { it == ';' || it == '"' || it == '\r' || it == '\n' }
            if (quoted) "\"" + text.replace("\"", "\"\"") + "\"" else text
        }
    }
}
