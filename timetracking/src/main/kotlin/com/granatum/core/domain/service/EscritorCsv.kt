package com.granatum.core.domain.service

import com.granatum.core.api.util.RangoFechas
import com.granatum.core.domain.model.FilaRegistro
import com.granatum.core.domain.model.TramoPausa
import com.granatum.core.domain.type.TipoContrato
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
object EscritorCsv {

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private const val SEPARADOR = ";"
    private const val FIN_DE_LINEA = "\r\n"
    private val PELIGROSOS = setOf('=', '+', '-', '@', '\t', '\r')

    private val FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(RangoFechas.ZONA)
    private val HORA = DateTimeFormatter.ofPattern("HH:mm").withZone(RangoFechas.ZONA)

    private const val DOCUMENTO = "Documento"

    private val COLUMNAS = listOf(
        "Persona", DOCUMENTO, "Puesto", "Fecha", "Entrada", "Salida", "Pausas",
        "Horas trabajadas", "Minutos trabajados", "Estado", "Completado a posteriori",
        "Corregido", "Entrada original", "Salida original", "Pausas originales", "Correcciones"
    )

    /**
     * The column is **omitted**, not left blank, when the requester may not see
     * the document: a "Documento" header over empty cells suggests the data
     * exists and is missing (D-012).
     */
    private fun columnas(incluirDocumento: Boolean) =
        if (incluirDocumento) COLUMNAS else COLUMNAS - DOCUMENTO

    fun escribirCabecera(out: OutputStream, incluirDocumento: Boolean) {
        out.write(BOM)
        escribirLinea(out, columnas(incluirDocumento))
    }

    fun escribirFila(out: OutputStream, fila: FilaRegistro, incluirDocumento: Boolean) {
        val celdas = buildList {
            add(fila.persona)
            if (incluirDocumento) add(fila.documento.orEmpty())
            add(fila.puesto)
            add(FECHA.format(fila.fecha))
            add(FECHA_HORA.format(fila.entrada))
            add(fila.salida?.let(FECHA_HORA::format).orEmpty())
            add(pausas(fila.pausas))
            add(fila.minutosTrabajados?.let(::horas).orEmpty())
            add(fila.minutosTrabajados?.toString().orEmpty())
            add(fila.estado.name)
            add(siNo(fila.completadoAPosteriori))
            add(siNo(fila.corregido))
            add(fila.original?.entrada?.let(FECHA_HORA::format).orEmpty())
            add(fila.original?.salida?.let(FECHA_HORA::format).orEmpty())
            add(fila.original?.pausas?.let(::pausas).orEmpty())
            add(
                fila.correcciones.joinToString(" | ") {
                    "${FECHA_HORA.format(it.resueltaEn)} solicitada por ${it.solicitante}, " +
                        "aprobada por ${it.aprobador}"
                }
            )
        }
        escribirLinea(out, celdas)
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
    fun escribirBloqueMensual(
        out: OutputStream,
        totalMinutos: Int,
        tipoContrato: TipoContrato,
        mesCerrado: Boolean,
        incluirDocumento: Boolean
    ) {
        val columnas = columnas(incluirDocumento)
        fun fila(vararg valores: Pair<Int, String>): List<String> =
            MutableList(columnas.size) { "" }.also { celdas -> valores.forEach { (i, v) -> celdas[i] = v } }

        out.write(FIN_DE_LINEA.toByteArray(Charsets.UTF_8))
        escribirLinea(
            out,
            fila(
                0 to "Total del mes",
                columnas.indexOf("Horas trabajadas") to horas(totalMinutos),
                columnas.indexOf("Minutos trabajados") to totalMinutos.toString()
            )
        )
        escribirLinea(out, fila(0 to "Tipo de contrato", 1 to tipoContrato.name))
        escribirLinea(out, fila(0 to "Mes cerrado", 1 to siNo(mesCerrado)))
    }

    private fun escribirLinea(out: OutputStream, celdas: List<String>) {
        out.write((celdas.joinToString(SEPARADOR, transform = ::celda) + FIN_DE_LINEA).toByteArray(Charsets.UTF_8))
    }

    private fun celda(valor: String): String {
        val peligroso = valor.isNotEmpty() && valor[0] in PELIGROSOS
        val texto = if (peligroso) "'$valor" else valor
        val comillas = peligroso || texto.any { it == ';' || it == '"' || it == '\r' || it == '\n' }
        return if (comillas) "\"" + texto.replace("\"", "\"\"") + "\"" else texto
    }

    /** `H:MM`, never padded on the hours: `0:05`, `8:30`, `26:30`. */
    private fun horas(minutos: Int): String = "${minutos / 60}:${(minutos % 60).toString().padStart(2, '0')}"

    private fun siNo(valor: Boolean) = if (valor) "Sí" else "No"

    /** `COMIDA 11:00-11:30`, comma-separated; an open break ends in `-`. */
    private fun pausas(pausas: List<TramoPausa>): String =
        pausas.joinToString(", ") { p ->
            "${p.tipo.name} ${HORA.format(p.inicio)}-${p.fin?.let(HORA::format).orEmpty()}"
        }
}
