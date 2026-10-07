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
 * and the spreadsheet would read it as text, breaking every sum. A [Numero]
 * accepts only a plain decimal with a comma - which no spreadsheet runs as a
 * formula - and is written as is; anything else is refused rather than written,
 * so the formula guard cannot be bypassed by calling a value a number.
 */
object FormatoCsv {

    sealed interface Celda

    /** Free or formatted text: protected against formulas and quoted as needed. */
    @JvmInline
    value class Texto(val valor: String) : Celda

    /** An amount with a comma decimal mark, e.g. `1764,00` or `-150,00`. */
    @JvmInline
    value class Numero(val valor: String) : Celda

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private const val SEPARADOR = ";"
    private const val FIN_DE_LINEA = "\r\n"
    private val PELIGROSOS = setOf('=', '+', '-', '@', '\t', '\r')
    private val DECIMAL = Regex("""-?\d+(,\d{1,2})?""")

    fun texto(valor: String): Celda = Texto(valor)

    /** @throws IllegalArgumentException if [valor] is not a plain decimal amount. */
    fun numero(valor: String): Celda {
        require(DECIMAL.matches(valor)) { "no es un importe decimal simple" }
        return Numero(valor)
    }

    fun escribirBom(out: OutputStream) = out.write(BOM)

    fun escribirLinea(out: OutputStream, celdas: List<Celda>) {
        out.write((celdas.joinToString(SEPARADOR, transform = ::formatear) + FIN_DE_LINEA).toByteArray(Charsets.UTF_8))
    }

    /** A row of text cells, the common case. */
    fun escribirLineaDeTexto(out: OutputStream, valores: List<String>) = escribirLinea(out, valores.map(::texto))

    fun escribirLineaVacia(out: OutputStream) = out.write(FIN_DE_LINEA.toByteArray(Charsets.UTF_8))

    private fun formatear(celda: Celda): String = when (celda) {
        is Numero -> celda.valor
        is Texto -> {
            val valor = celda.valor
            val peligroso = valor.isNotEmpty() && valor[0] in PELIGROSOS
            val texto = if (peligroso) "'$valor" else valor
            val comillas = peligroso || texto.any { it == ';' || it == '"' || it == '\r' || it == '\n' }
            if (comillas) "\"" + texto.replace("\"", "\"\"") + "\"" else texto
        }
    }
}
