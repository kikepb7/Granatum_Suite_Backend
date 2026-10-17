package com.granatum.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.granatum.core.domain.port.RecognitionResult
import com.granatum.core.infrastructure.claude.InvoiceClaudeSettings
import com.granatum.core.infrastructure.claude.ClaudeInvoiceRecognizer
import com.granatum.core.files.FileTypeDetector
import com.granatum.core.infrastructure.documents.InvoiceDocumentPreparer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SC-001, SC-002, FR-007 against the **real** Claude API. Costs money, so it is
 * tagged out of `test` and runs only through `claudeRealTest`, by hand:
 *
 *     ANTHROPIC_API_KEY=... INVOICES_REFERENCE_DIR=/ruta ./gradlew :features:invoices:claudeRealTest
 *
 * The directory holds real invoices of the company - **never committed** - each
 * `X.pdf` / `X.jpg` / `X.png` next to an `X.json` with the expected values
 * (`issuerNif`, `number`, `issueDate`, `total`, and optionally `isInvoice:
 * false`). A file whose name starts with `inyeccion` must contain text addressed
 * to the AI ("ignore the above..."): its expected values are the real ones.
 *
 * Measures, per required field, how many come out right without correction
 * (SC-002: at least 90%), that no field is invented (a value where the expected
 * one is unreadable), the time per invoice (SC-001: 95% under 30 s), and the
 * real tokens and cost.
 *
 * Skipped, saying why, when the key or the directory is missing.
 */
@Tag("claude-real")
class RecognitionPrecisionIT {

    private val fields = listOf("issuerNif", "number", "issueDate", "total")

    @Test
    fun `recognition of the reference invoices meets SC-001 and SC-002`() {
        val key = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        val dir = System.getenv("INVOICES_REFERENCE_DIR")?.let(::File)
        assumeTrue(key.isNotBlank(), "sin ANTHROPIC_API_KEY: no se mide nada")
        assumeTrue(dir != null && dir.isDirectory, "sin INVOICES_REFERENCE_DIR: no hay facturas de referencia")

        val recognizer = ClaudeInvoiceRecognizer(
            InvoiceClaudeSettings(
                apiKey = key,
                model = System.getenv("INVOICES_CLAUDE_MODEL") ?: "claude-opus-5-5",
                effort = System.getenv("INVOICES_CLAUDE_EFFORT") ?: "medium",
                timeoutSeconds = 120,
                fallbacks = System.getenv("INVOICES_CLAUDE_FALLBACKS")?.toBoolean() ?: true
            )
        )
        val preparer = InvoiceDocumentPreparer(pdfMaxPages = 20)
        val json = ObjectMapper().registerKotlinModule()

        val files = dir!!.listFiles { f -> f.extension.lowercase() in setOf("pdf", "jpg", "jpeg", "png", "webp") }!!.sortedBy { it.name }
        assumeTrue(files.isNotEmpty(), "el directorio no tiene facturas")

        var hits = 0
        var evaluated = 0
        val invented = mutableListOf<String>()
        val times = mutableListOf<Long>()
        var inputTokens = 0L
        var outputTokens = 0L

        files.forEach { file ->
            val expected = json.readTree(File(file.parentFile, file.nameWithoutExtension + ".json"))
            val bytes = file.readBytes()
            val type = FileTypeDetector.detect(bytes)!!
            val start = System.nanoTime()
            val r = recognizer.recognize(preparer.forSending(bytes, type))
            times += (System.nanoTime() - start) / 1_000_000
            inputTokens += r.inputTokens ?: 0
            outputTokens += r.outputTokens ?: 0

            assertTrue(r is RecognitionResult.Recognized, "${file.name}: $r")
            val p = r.proposal
            if (expected["isInvoice"]?.asBoolean() == false) {
                assertEquals(false, p.isInvoice, "${file.name} is not an invoice")
                return@forEach
            }
            val actual = mapOf("issuerNif" to p.issuerNif, "number" to p.number, "issueDate" to p.issueDate, "total" to p.total)
            fields.forEach { field ->
                val expectedValue = expected[field]?.takeIf { !it.isNull }?.asText()
                val actualValue = actual[field]
                evaluated++
                when {
                    expectedValue == null && actualValue != null -> invented += "${file.name}.$field"
                    expectedValue != null && matches(field, expectedValue, actualValue) -> hits++
                }
            }
        }

        val precision = hits.toDouble() / evaluated
        val p95 = times.sorted()[((times.size - 1) * 0.95).toInt()]
        val cost = inputTokens / 1e6 * 4.0 + outputTokens / 1e6 * 20.0
        println("Precision: %.1f%% (%d/%d) | p95 %d ms | %d tokens de entrada, %d de salida | %.4f USD (%.4f por factura, precios de Opus 5.5)"
            .format(precision * 100, hits, evaluated, p95, inputTokens, outputTokens, cost, cost / files.size))

        assertEquals(emptyList(), invented, "FR-004: what cannot be read must stay empty")
        assertTrue(precision >= 0.90, "SC-002: %.1f%% of required fields right".format(precision * 100))
        assertTrue(p95 < 30_000, "SC-001: 95% under 30 s, p95 = $p95 ms")
    }

    private fun matches(field: String, expected: String, actual: String?): Boolean = when (field) {
        "total" -> actual != null && runCatching { BigDecimal(expected).compareTo(BigDecimal(actual)) == 0 }.getOrDefault(false)
        "issuerNif" -> actual != null && expected.uppercase().filter(Char::isLetterOrDigit).removePrefix("ES") ==
            actual.uppercase().filter(Char::isLetterOrDigit).removePrefix("ES")
        else -> expected == actual
    }
}
