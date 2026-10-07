package com.granatum.core

import com.granatum.core.domain.model.CorreccionAplicada
import com.granatum.core.domain.model.FilaRegistro
import com.granatum.core.domain.model.TramoPausa
import com.granatum.core.domain.model.ValoresOriginales
import com.granatum.core.domain.service.EscritorCsv
import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.domain.type.TipoPausa
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
class EscritorCsvTest {

    private val madrid = ZoneId.of("Europe/Madrid")

    private fun instante(fecha: String, hora: String) =
        LocalDateTime.of(LocalDate.parse(fecha), java.time.LocalTime.parse(hora)).atZone(madrid).toInstant()

    private fun fila(
        persona: String = "Ana Pérez",
        documento: String? = "12345678Z",
        puesto: String = "Florista",
        minutos: Int? = 510,
        estado: EstadoFichaje = EstadoFichaje.CERRADO,
        salida: String? = "16:00",
        original: ValoresOriginales? = null,
        correcciones: List<CorreccionAplicada> = emptyList()
    ) = FilaRegistro(
        persona = persona,
        documento = documento,
        puesto = puesto,
        fecha = LocalDate.parse("2026-10-05"),
        entrada = instante("2026-10-05", "07:00"),
        salida = salida?.let { instante("2026-10-05", it) },
        pausas = listOf(
            TramoPausa(TipoPausa.COMIDA, instante("2026-10-05", "11:00"), instante("2026-10-05", "11:30"))
        ),
        minutosTrabajados = minutos,
        estado = estado,
        completadoAPosteriori = false,
        corregido = correcciones.isNotEmpty(),
        original = original,
        correcciones = correcciones
    )

    private fun escribir(
        filas: List<FilaRegistro>,
        incluirDocumento: Boolean = true,
        bloque: Triple<Int, TipoContrato, Boolean>? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        EscritorCsv.escribirCabecera(out, incluirDocumento)
        filas.forEach { EscritorCsv.escribirFila(out, it, incluirDocumento) }
        bloque?.let { (total, tipo, cerrado) ->
            EscritorCsv.escribirBloqueMensual(out, total, tipo, cerrado, incluirDocumento)
        }
        return out.toByteArray()
    }

    /** The text after the BOM, split into lines on CRLF. */
    private fun lineas(bytes: ByteArray): List<String> =
        String(bytes, 3, bytes.size - 3, Charsets.UTF_8).removeSuffix("\r\n").split("\r\n")

    /**
     * Splits one line into cells following RFC 4180, so the column count is
     * checked the way a spreadsheet would read it rather than by counting `;`.
     */
    private fun celdas(linea: String): List<String> {
        val resultado = mutableListOf<String>()
        val actual = StringBuilder()
        var entreComillas = false
        var i = 0
        while (i < linea.length) {
            val c = linea[i]
            when {
                entreComillas && c == '"' && i + 1 < linea.length && linea[i + 1] == '"' -> {
                    actual.append('"'); i++
                }
                c == '"' -> entreComillas = !entreComillas
                c == ';' && !entreComillas -> { resultado += actual.toString(); actual.clear() }
                else -> actual.append(c)
            }
            i++
        }
        resultado += actual.toString()
        return resultado
    }

    private val cabeceraCompleta = listOf(
        "Persona", "Documento", "Puesto", "Fecha", "Entrada", "Salida", "Pausas",
        "Horas trabajadas", "Minutos trabajados", "Estado", "Completado a posteriori",
        "Corregido", "Entrada original", "Salida original", "Pausas originales", "Correcciones"
    )

    @Test
    fun `the file starts with the UTF-8 BOM`() {
        val bytes = escribir(listOf(fila()))
        assertContentEquals(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            bytes.copyOfRange(0, 3),
            "without the BOM, Excel on Windows reads the file as ANSI and mangles accents"
        )
    }

    @Test
    fun `lines end in CRLF and cells are separated by semicolons`() {
        val texto = String(escribir(listOf(fila())), Charsets.UTF_8)
        assertTrue(texto.endsWith("\r\n"))
        assertFalse(texto.replace("\r\n", "").contains('\n'), "no bare LF")
        assertEquals(cabeceraCompleta.joinToString(";"), lineas(escribir(emptyList())).single())
    }

    @Test
    fun `a closed day is written in the documented formats`() {
        val linea = lineas(escribir(listOf(fila()))).last()
        assertEquals(
            "Ana Pérez;12345678Z;Florista;2026-10-05;2026-10-05 07:00;2026-10-05 16:00;" +
                "COMIDA 11:00-11:30;8:30;510;CERRADO;No;No;;;;",
            linea
        )
    }

    @Test
    fun `an open day leaves hours and minutes empty, never zero`() {
        val celdas = celdas(lineas(escribir(listOf(fila(minutos = null, salida = null, estado = EstadoFichaje.EN_CURSO)))).last())
        assertEquals("", celdas[cabeceraCompleta.indexOf("Salida")])
        assertEquals("", celdas[cabeceraCompleta.indexOf("Horas trabajadas")])
        assertEquals("", celdas[cabeceraCompleta.indexOf("Minutos trabajados")])
        assertEquals("EN_CURSO", celdas[cabeceraCompleta.indexOf("Estado")])
    }

    @Test
    fun `values with separators, quotes or line breaks are quoted and keep the column count`() {
        val filas = listOf(
            fila(persona = "Pérez; Ana"),
            fila(puesto = "Florista \"senior\""),
            fila(puesto = "Línea uno\r\nlínea dos"),
            fila(puesto = "Solo\nLF")
        )
        val texto = String(escribir(filas), Charsets.UTF_8).removePrefix("﻿")

        assertTrue(texto.contains("\"Pérez; Ana\""))
        assertTrue(texto.contains("\"Florista \"\"senior\"\"\""), "inner quotes doubled")

        // Split as a spreadsheet would: on CRLF outside quotes.
        val registros = mutableListOf<String>()
        val actual = StringBuilder()
        var dentro = false
        var i = 0
        while (i < texto.length) {
            val c = texto[i]
            if (c == '"') dentro = !dentro
            if (!dentro && c == '\r' && i + 1 < texto.length && texto[i + 1] == '\n') {
                registros += actual.toString(); actual.clear(); i += 2; continue
            }
            actual.append(c); i++
        }
        assertEquals(5, registros.size, "header plus four rows, whatever the values contain")
        registros.forEach { assertEquals(cabeceraCompleta.size, celdas(it).size, "FR-010: $it") }
        assertEquals("Línea uno\r\nlínea dos", celdas(registros[3])[2])
    }

    @Test
    fun `a value that would run as a formula is neutralised`() {
        val peligrosos = listOf("=1+1", "+34 600", "-2+3", "@SUM(A1)", "\tTab", "\rCR")
        peligrosos.forEach { valor ->
            // A lone CR is not a line end, so the record is still the last line.
            val celda = celdas(lineas(escribir(listOf(fila(persona = valor)))).last())[0]
            assertEquals("'$valor", celda, "SC-006: '$valor' must be text, not a formula")
        }
        val bruto = String(escribir(listOf(fila(persona = "=HYPERLINK(\"x\")"))), Charsets.UTF_8)
        assertTrue(bruto.contains("\"'=HYPERLINK(\"\"x\"\")\""), "prefixed and quoted")
    }

    @Test
    fun `no legitimate value is mistaken for a formula`() {
        val texto = String(
            escribir(
                listOf(
                    fila(minutos = 5),
                    fila(minutos = 510),
                    fila(minutos = 720),
                    fila(
                        original = ValoresOriginales(instante("2026-10-05", "06:55"), null, emptyList()),
                        correcciones = listOf(
                            CorreccionAplicada(instante("2026-10-07", "09:12"), "Ana Pérez", "Luis Gil")
                        )
                    )
                )
            ),
            Charsets.UTF_8
        )
        assertFalse(texto.contains("'"), "dates, times, H:MM and minutes never start with a dangerous character")
        assertTrue(texto.contains(";0:05;5;"))
        assertTrue(texto.contains(";8:30;510;"))
        assertTrue(texto.contains(";12:00;720;"))
    }

    @Test
    fun `flags are written as Si and No`() {
        val fila = fila(
            original = ValoresOriginales(
                instante("2026-10-05", "07:00"),
                instante("2026-10-05", "15:00"),
                listOf(TramoPausa(TipoPausa.DESCANSO, instante("2026-10-05", "10:00"), instante("2026-10-05", "10:15")))
            ),
            correcciones = listOf(CorreccionAplicada(instante("2026-10-07", "09:12"), "Ana Pérez", "Luis Gil"))
        ).copy(completadoAPosteriori = true)
        val celdas = celdas(lineas(escribir(listOf(fila))).last())
        assertEquals("Sí", celdas[cabeceraCompleta.indexOf("Completado a posteriori")])
        assertEquals("Sí", celdas[cabeceraCompleta.indexOf("Corregido")])
        assertEquals("2026-10-05 07:00", celdas[cabeceraCompleta.indexOf("Entrada original")])
        assertEquals("2026-10-05 15:00", celdas[cabeceraCompleta.indexOf("Salida original")])
        assertEquals("DESCANSO 10:00-10:15", celdas[cabeceraCompleta.indexOf("Pausas originales")])
    }

    @Test
    fun `corrections are listed oldest first with requester and approver`() {
        val fila = fila(
            correcciones = listOf(
                CorreccionAplicada(instante("2026-10-07", "09:12"), "Ana Pérez", "Luis Gil"),
                CorreccionAplicada(instante("2026-10-09", "17:40"), "Ana Pérez", "Marta Ruiz")
            )
        )
        assertEquals(
            "2026-10-07 09:12 solicitada por Ana Pérez, aprobada por Luis Gil | " +
                "2026-10-09 17:40 solicitada por Ana Pérez, aprobada por Marta Ruiz",
            celdas(lineas(escribir(listOf(fila))).last())[cabeceraCompleta.indexOf("Correcciones")]
        )
    }

    @Test
    fun `without the document the column does not exist at all`() {
        val lineas = lineas(escribir(listOf(fila(documento = null)), incluirDocumento = false))
        val cabecera = celdas(lineas.first())
        assertEquals(cabeceraCompleta - "Documento", cabecera)
        assertFalse(lineas.last().contains("12345678Z"))
        assertEquals(cabecera.size, celdas(lineas.last()).size)
        assertEquals("Florista", celdas(lineas.last())[1], "everything shifts one position")
    }

    @Test
    fun `the same rows produce the same bytes`() {
        val filas = listOf(fila(), fila(persona = "Zoe"), fila(minutos = null, salida = null))
        assertContentEquals(escribir(filas), escribir(filas), "FR-011")
    }

    @Test
    fun `the monthly block puts the total under the hours and minutes columns, with and without the document`() {
        listOf(true, false).forEach { conDocumento ->
            val lineas = lineas(
                escribir(
                    listOf(fila(documento = if (conDocumento) "12345678Z" else null)),
                    incluirDocumento = conDocumento,
                    bloque = Triple(1590, TipoContrato.PARCIAL, false)
                )
            )
            val cabecera = celdas(lineas[0])
            val vacia = lineas.indexOf("")
            assertEquals(2, vacia, "a blank line separates the rows from the block")

            val total = celdas(lineas[vacia + 1])
            assertEquals("Total del mes", total[0])
            assertEquals("26:30", total[cabecera.indexOf("Horas trabajadas")], "documento=$conDocumento")
            assertEquals("1590", total[cabecera.indexOf("Minutos trabajados")], "documento=$conDocumento")
            assertEquals(cabecera.size, total.size)

            assertEquals(listOf("Tipo de contrato", "PARCIAL"), celdas(lineas[vacia + 2]).take(2))
            assertEquals(listOf("Mes cerrado", "No"), celdas(lineas[vacia + 3]).take(2))
            assertEquals(vacia + 4, lineas.size)
        }
    }
}
