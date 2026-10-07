package com.granatum.core.csv

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The spreadsheet CSV shared by every feature that hands out files (feature 003's
 * register export, feature 004's invoice reports).
 *
 * The cell rules came from `EscritorCsvTest` in timetracking, which still runs
 * unchanged against the export: that is the proof that moving them here changed
 * nothing. The numeric cell is new with feature 004 (finding I2).
 */
class FormatoCsvTest {

    private fun linea(vararg celdas: FormatoCsv.Celda): String {
        val out = ByteArrayOutputStream()
        FormatoCsv.escribirLinea(out, celdas.toList())
        return out.toString(Charsets.UTF_8)
    }

    private fun texto(v: String) = linea(FormatoCsv.texto(v)).removeSuffix("\r\n")

    @Test
    fun `the BOM is the three UTF-8 bytes`() {
        val out = ByteArrayOutputStream()
        FormatoCsv.escribirBom(out)
        assertContentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), out.toByteArray())
    }

    @Test
    fun `cells are separated by semicolons and lines end in CRLF`() {
        assertEquals("a;b;c\r\n", linea(FormatoCsv.texto("a"), FormatoCsv.texto("b"), FormatoCsv.texto("c")))
        val vacia = ByteArrayOutputStream().also { FormatoCsv.escribirLineaVacia(it) }
        assertEquals("\r\n", vacia.toString(Charsets.UTF_8))
    }

    @Test
    fun `values with separators, quotes or line breaks are quoted with inner quotes doubled`() {
        assertEquals("\"Pérez; Ana\"", texto("Pérez; Ana"))
        assertEquals("\"Florista \"\"senior\"\"\"", texto("Florista \"senior\""))
        assertEquals("\"uno\r\ndos\"", texto("uno\r\ndos"))
        assertEquals("\"solo\nLF\"", texto("solo\nLF"))
    }

    @Test
    fun `a text cell that would run as a formula is neutralised`() {
        listOf("=1+1", "+34 600", "-2+3", "@SUM(A1)", "\tTab", "\rCR", "-150,00").forEach {
            assertEquals("\"'${it.replace("\"", "\"\"")}\"", texto(it), "text '$it' must not run")
        }
    }

    @Test
    fun `no legitimate text value is mistaken for a formula`() {
        listOf("2026-10-05", "2026-10-05 07:00", "8:30", "0:05", "510", "1764,00", "Ana Pérez").forEach {
            assertEquals(it, texto(it))
        }
    }

    /**
     * Feature 004: a negative total from credit notes is a number, not a formula.
     * Written as text it would get the apostrophe and Excel would read it as
     * text, breaking every sum - and disagreeing with the JSON (SC-010).
     */
    @Test
    fun `a numeric cell writes amounts, negative ones included, without the apostrophe`() {
        assertEquals("1764,00\r\n", linea(FormatoCsv.numero("1764,00")))
        assertEquals("-150,00\r\n", linea(FormatoCsv.numero("-150,00")))
        assertEquals("0\r\n", linea(FormatoCsv.numero("0")))
        assertEquals("12,5\r\n", linea(FormatoCsv.numero("12,5")))
    }

    @Test
    fun `a numeric cell refuses anything that is not a plain decimal`() {
        listOf("-2+3", "=1", "-150,00x", "1.764,00", "1764.00", "", "- 5", "+5", "1,234").forEach {
            assertFailsWith<IllegalArgumentException>("'$it' is not a plain amount") { FormatoCsv.numero(it) }
        }
    }
}
