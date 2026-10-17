package com.granatum.core

/**
 * Reads an exported file back the way a spreadsheet would: BOM stripped,
 * records split on CRLF outside quotes, cells split on `;` outside quotes and
 * `""` unescaped (RFC 4180). The tests assert on cells, never on raw `;`
 * counts, so a quoting bug cannot hide behind a lucky separator count.
 */
object RegisterCsvReader {

    fun records(bytes: ByteArray): List<List<String>> {
        val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        val records = mutableListOf<List<String>>()
        var cells = mutableListOf<String>()
        val current = StringBuilder()
        var inside = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inside && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> inside = !inside
                !inside && c == ';' -> { cells += current.toString(); current.clear() }
                !inside && c == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> {
                    cells += current.toString(); current.clear()
                    records += cells; cells = mutableListOf()
                    i++
                }
                else -> current.append(c)
            }
            i++
        }
        return records
    }

    /** Data rows as maps keyed by header, stopping at the blank line of the monthly block. */
    fun rows(bytes: ByteArray): List<Map<String, String>> {
        val allRecords = records(bytes)
        val header = allRecords.first()
        return allRecords.drop(1)
            .takeWhile { it != listOf("") }
            .map { row -> header.zip(row).toMap() }
    }

    fun header(bytes: ByteArray): List<String> = records(bytes).first()
}
