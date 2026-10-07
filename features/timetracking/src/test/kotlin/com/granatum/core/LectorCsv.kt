package com.granatum.core

/**
 * Reads an exported file back the way a spreadsheet would: BOM stripped,
 * records split on CRLF outside quotes, cells split on `;` outside quotes and
 * `""` unescaped (RFC 4180). The tests assert on cells, never on raw `;`
 * counts, so a quoting bug cannot hide behind a lucky separator count.
 */
object LectorCsv {

    fun registros(bytes: ByteArray): List<List<String>> {
        val texto = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        val registros = mutableListOf<List<String>>()
        var celdas = mutableListOf<String>()
        val actual = StringBuilder()
        var dentro = false
        var i = 0
        while (i < texto.length) {
            val c = texto[i]
            when {
                dentro && c == '"' && i + 1 < texto.length && texto[i + 1] == '"' -> { actual.append('"'); i++ }
                c == '"' -> dentro = !dentro
                !dentro && c == ';' -> { celdas += actual.toString(); actual.clear() }
                !dentro && c == '\r' && i + 1 < texto.length && texto[i + 1] == '\n' -> {
                    celdas += actual.toString(); actual.clear()
                    registros += celdas; celdas = mutableListOf()
                    i++
                }
                else -> actual.append(c)
            }
            i++
        }
        return registros
    }

    /** Data rows as maps keyed by header, stopping at the blank line of the monthly block. */
    fun filas(bytes: ByteArray): List<Map<String, String>> {
        val todos = registros(bytes)
        val cabecera = todos.first()
        return todos.drop(1)
            .takeWhile { it != listOf("") }
            .map { fila -> cabecera.zip(fila).toMap() }
    }

    fun cabecera(bytes: ByteArray): List<String> = registros(bytes).first()
}
