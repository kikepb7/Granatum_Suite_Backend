package com.granatum.core

import com.granatum.core.domain.model.AppliedCorrection
import com.granatum.core.domain.model.RegisterRow
import com.granatum.core.domain.model.BreakSpan
import com.granatum.core.domain.model.OriginalShiftValues
import com.granatum.core.domain.service.RegisterCsvWriter
import com.granatum.core.domain.type.ShiftStatus
import com.granatum.core.domain.type.ContractType
import com.granatum.core.domain.type.BreakType
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The file format, byte by byte (D-004, D-005).
 *
 * BOM and `;` are what make the file open without an import wizard in a
 * Spanish-locale spreadsheet (FR-008, SC-005); the formula guard is SC-006.
 */
class RegisterCsvWriterTest {

    private val madrid = ZoneId.of("Europe/Madrid")

    private fun instantOf(date: String, time: String) =
        LocalDateTime.of(LocalDate.parse(date), java.time.LocalTime.parse(time)).atZone(madrid).toInstant()

    private fun row(
        person: String = "Ana Pérez",
        document: String? = "12345678Z",
        position: String = "Florista",
        minutes: Int? = 510,
        status: ShiftStatus = ShiftStatus.CLOSED,
        clockOut: String? = "16:00",
        original: OriginalShiftValues? = null,
        corrections: List<AppliedCorrection> = emptyList()
    ) = RegisterRow(
        person = person,
        document = document,
        position = position,
        date = LocalDate.parse("2026-10-05"),
        clockIn = instantOf("2026-10-05", "07:00"),
        clockOut = clockOut?.let { instantOf("2026-10-05", it) },
        breaks = listOf(
            BreakSpan(BreakType.MEAL, instantOf("2026-10-05", "11:00"), instantOf("2026-10-05", "11:30"))
        ),
        workedMinutes = minutes,
        status = status,
        completedAfterwards = false,
        corrected = corrections.isNotEmpty(),
        original = original,
        corrections = corrections
    )

    private fun write(
        rows: List<RegisterRow>,
        includeDocument: Boolean = true,
        chunk: Triple<Int, ContractType, Boolean>? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        RegisterCsvWriter.writeHeader(out, includeDocument)
        rows.forEach { RegisterCsvWriter.writeRow(out, it, includeDocument) }
        chunk?.let { (total, type, closed) ->
            RegisterCsvWriter.writeMonthlyBlock(out, total, type, closed, includeDocument)
        }
        return out.toByteArray()
    }

    /** The text after the BOM, split into lines on CRLF. */
    private fun lines(bytes: ByteArray): List<String> =
        String(bytes, 3, bytes.size - 3, Charsets.UTF_8).removeSuffix("\r\n").split("\r\n")

    /**
     * Splits one line into cells following RFC 4180, so the column count is
     * checked the way a spreadsheet would read it rather than by counting `;`.
     */
    private fun cells(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == ';' && !quoted -> { result += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        result += current.toString()
        return result
    }

    private val fullHeader = listOf(
        "Persona", "Documento", "Puesto", "Fecha", "Entrada", "Salida", "Pausas",
        "Horas trabajadas", "Minutos trabajados", "Estado", "Completado a posteriori",
        "Corregido", "Entrada original", "Salida original", "Pausas originales", "Correcciones"
    )

    @Test
    fun `the file starts with the UTF-8 BOM`() {
        val bytes = write(listOf(row()))
        assertContentEquals(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            bytes.copyOfRange(0, 3),
            "without the BOM, Excel on Windows reads the file as ANSI and mangles accents"
        )
    }

    @Test
    fun `lines end in CRLF and cells are separated by semicolons`() {
        val text = String(write(listOf(row())), Charsets.UTF_8)
        assertTrue(text.endsWith("\r\n"))
        assertFalse(text.replace("\r\n", "").contains('\n'), "no bare LF")
        assertEquals(fullHeader.joinToString(";"), lines(write(emptyList())).single())
    }

    @Test
    fun `a closed day is written in the documented formats`() {
        val line = lines(write(listOf(row()))).last()
        assertEquals(
            "Ana Pérez;12345678Z;Florista;2026-10-05;2026-10-05 07:00;2026-10-05 16:00;" +
                "COMIDA 11:00-11:30;8:30;510;CERRADO;No;No;;;;",
            line
        )
    }

    @Test
    fun `an open day leaves hours and minutes empty, never zero`() {
        val cells = cells(lines(write(listOf(row(minutes = null, clockOut = null, status = ShiftStatus.IN_PROGRESS)))).last())
        assertEquals("", cells[fullHeader.indexOf("Salida")])
        assertEquals("", cells[fullHeader.indexOf("Horas trabajadas")])
        assertEquals("", cells[fullHeader.indexOf("Minutos trabajados")])
        assertEquals("EN_CURSO", cells[fullHeader.indexOf("Estado")])
    }

    @Test
    fun `values with separators, quotes or line breaks are quoted and keep the column count`() {
        val rows = listOf(
            row(person = "Pérez; Ana"),
            row(position = "Florista \"senior\""),
            row(position = "Línea uno\r\nlínea dos"),
            row(position = "Solo\nLF")
        )
        val text = String(write(rows), Charsets.UTF_8).removePrefix("﻿")

        assertTrue(text.contains("\"Pérez; Ana\""))
        assertTrue(text.contains("\"Florista \"\"senior\"\"\""), "inner quotes doubled")

        // Split as a spreadsheet would: on CRLF outside quotes.
        val records = mutableListOf<String>()
        val current = StringBuilder()
        var inside = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"') inside = !inside
            if (!inside && c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                records += current.toString(); current.clear(); i += 2; continue
            }
            current.append(c); i++
        }
        assertEquals(5, records.size, "header plus four rows, whatever the values contain")
        records.forEach { assertEquals(fullHeader.size, cells(it).size, "FR-010: $it") }
        assertEquals("Línea uno\r\nlínea dos", cells(records[3])[2])
    }

    @Test
    fun `a value that would run as a formula is neutralised`() {
        val dangerous = listOf("=1+1", "+34 600", "-2+3", "@SUM(A1)", "\tTab", "\rCR")
        dangerous.forEach { value ->
            // A lone CR is not a line end, so the record is still the last line.
            val cell = cells(lines(write(listOf(row(person = value)))).last())[0]
            assertEquals("'$value", cell, "SC-006: '$value' must be text, not a formula")
        }
        val raw = String(write(listOf(row(person = "=HYPERLINK(\"x\")"))), Charsets.UTF_8)
        assertTrue(raw.contains("\"'=HYPERLINK(\"\"x\"\")\""), "prefixed and quoted")
    }

    @Test
    fun `no legitimate value is mistaken for a formula`() {
        val text = String(
            write(
                listOf(
                    row(minutes = 5),
                    row(minutes = 510),
                    row(minutes = 720),
                    row(
                        original = OriginalShiftValues(instantOf("2026-10-05", "06:55"), null, emptyList()),
                        corrections = listOf(
                            AppliedCorrection(instantOf("2026-10-07", "09:12"), "Ana Pérez", "Luis Gil")
                        )
                    )
                )
            ),
            Charsets.UTF_8
        )
        assertFalse(text.contains("'"), "dates, times, H:MM and minutes never start with a dangerous character")
        assertTrue(text.contains(";0:05;5;"))
        assertTrue(text.contains(";8:30;510;"))
        assertTrue(text.contains(";12:00;720;"))
    }

    @Test
    fun `flags are written as Si and No`() {
        val row = row(
            original = OriginalShiftValues(
                instantOf("2026-10-05", "07:00"),
                instantOf("2026-10-05", "15:00"),
                listOf(BreakSpan(BreakType.REST, instantOf("2026-10-05", "10:00"), instantOf("2026-10-05", "10:15")))
            ),
            corrections = listOf(AppliedCorrection(instantOf("2026-10-07", "09:12"), "Ana Pérez", "Luis Gil"))
        ).copy(completedAfterwards = true)
        val cells = cells(lines(write(listOf(row))).last())
        assertEquals("Sí", cells[fullHeader.indexOf("Completado a posteriori")])
        assertEquals("Sí", cells[fullHeader.indexOf("Corregido")])
        assertEquals("2026-10-05 07:00", cells[fullHeader.indexOf("Entrada original")])
        assertEquals("2026-10-05 15:00", cells[fullHeader.indexOf("Salida original")])
        assertEquals("DESCANSO 10:00-10:15", cells[fullHeader.indexOf("Pausas originales")])
    }

    @Test
    fun `corrections are listed oldest first with requester and approver`() {
        val row = row(
            corrections = listOf(
                AppliedCorrection(instantOf("2026-10-07", "09:12"), "Ana Pérez", "Luis Gil"),
                AppliedCorrection(instantOf("2026-10-09", "17:40"), "Ana Pérez", "Marta Ruiz")
            )
        )
        assertEquals(
            "2026-10-07 09:12 solicitada por Ana Pérez, aprobada por Luis Gil | " +
                "2026-10-09 17:40 solicitada por Ana Pérez, aprobada por Marta Ruiz",
            cells(lines(write(listOf(row))).last())[fullHeader.indexOf("Correcciones")]
        )
    }

    @Test
    fun `without the document the column does not exist at all`() {
        val lines = lines(write(listOf(row(document = null)), includeDocument = false))
        val header = cells(lines.first())
        assertEquals(fullHeader - "Documento", header)
        assertFalse(lines.last().contains("12345678Z"))
        assertEquals(header.size, cells(lines.last()).size)
        assertEquals("Florista", cells(lines.last())[1], "everything shifts one position")
    }

    @Test
    fun `the same rows produce the same bytes`() {
        val rows = listOf(row(), row(person = "Zoe"), row(minutes = null, clockOut = null))
        assertContentEquals(write(rows), write(rows), "FR-011")
    }

    @Test
    fun `the monthly block puts the total under the hours and minutes columns, with and without the document`() {
        listOf(true, false).forEach { withDocument ->
            val lines = lines(
                write(
                    listOf(row(document = if (withDocument) "12345678Z" else null)),
                    includeDocument = withDocument,
                    chunk = Triple(1590, ContractType.PART_TIME, false)
                )
            )
            val header = cells(lines[0])
            val blankLine = lines.indexOf("")
            assertEquals(2, blankLine, "a blank line separates the rows from the block")

            val total = cells(lines[blankLine + 1])
            assertEquals("Total del mes", total[0])
            assertEquals("26:30", total[header.indexOf("Horas trabajadas")], "documento=$withDocument")
            assertEquals("1590", total[header.indexOf("Minutos trabajados")], "documento=$withDocument")
            assertEquals(header.size, total.size)

            assertEquals(listOf("Tipo de contrato", "PARCIAL"), cells(lines[blankLine + 2]).take(2))
            assertEquals(listOf("Mes cerrado", "No"), cells(lines[blankLine + 3]).take(2))
            assertEquals(blankLine + 4, lines.size)
        }
    }
}
