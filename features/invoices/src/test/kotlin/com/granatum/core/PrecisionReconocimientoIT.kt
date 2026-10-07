package com.granatum.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.granatum.core.domain.port.ResultadoReconocimiento
import com.granatum.core.infrastructure.claude.ConfiguracionClaude
import com.granatum.core.infrastructure.claude.ReconocedorClaude
import com.granatum.core.infrastructure.documentos.DetectorTipoFichero
import com.granatum.core.infrastructure.documentos.PreparadorDocumento
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
 *     ANTHROPIC_API_KEY=... INVOICES_REFERENCIA_DIR=/ruta ./gradlew :features:invoices:claudeRealTest
 *
 * The directory holds real invoices of the company - **never committed** - each
 * `X.pdf` / `X.jpg` / `X.png` next to an `X.json` with the expected values
 * (`emisorNif`, `numero`, `fechaEmision`, `total`, and optionally `esFactura:
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
class PrecisionReconocimientoIT {

    private val campos = listOf("emisorNif", "numero", "fechaEmision", "total")

    @Test
    fun `recognition of the reference invoices meets SC-001 and SC-002`() {
        val clave = System.getenv("ANTHROPIC_API_KEY").orEmpty()
        val dir = System.getenv("INVOICES_REFERENCIA_DIR")?.let(::File)
        assumeTrue(clave.isNotBlank(), "sin ANTHROPIC_API_KEY: no se mide nada")
        assumeTrue(dir != null && dir.isDirectory, "sin INVOICES_REFERENCIA_DIR: no hay facturas de referencia")

        val reconocedor = ReconocedorClaude(
            ConfiguracionClaude(
                apiKey = clave,
                modelo = System.getenv("INVOICES_CLAUDE_MODEL") ?: "claude-opus-5-5",
                esfuerzo = System.getenv("INVOICES_CLAUDE_EFFORT") ?: "medium",
                timeoutSegundos = 120,
                fallbacks = System.getenv("INVOICES_CLAUDE_FALLBACKS")?.toBoolean() ?: true
            )
        )
        val preparador = PreparadorDocumento(pdfMaxPaginas = 20)
        val json = ObjectMapper().registerKotlinModule()

        val ficheros = dir!!.listFiles { f -> f.extension.lowercase() in setOf("pdf", "jpg", "jpeg", "png", "webp") }!!.sortedBy { it.name }
        assumeTrue(ficheros.isNotEmpty(), "el directorio no tiene facturas")

        var aciertos = 0
        var evaluados = 0
        val inventados = mutableListOf<String>()
        val tiempos = mutableListOf<Long>()
        var tokensEntrada = 0L
        var tokensSalida = 0L

        ficheros.forEach { fichero ->
            val esperado = json.readTree(File(fichero.parentFile, fichero.nameWithoutExtension + ".json"))
            val bytes = fichero.readBytes()
            val tipo = DetectorTipoFichero.detectar(bytes)!!
            val inicio = System.nanoTime()
            val r = reconocedor.reconocer(preparador.paraEnviar(bytes, tipo))
            tiempos += (System.nanoTime() - inicio) / 1_000_000
            tokensEntrada += r.tokensEntrada ?: 0
            tokensSalida += r.tokensSalida ?: 0

            assertTrue(r is ResultadoReconocimiento.Reconocida, "${fichero.name}: $r")
            val p = r.propuesta
            if (esperado["esFactura"]?.asBoolean() == false) {
                assertEquals(false, p.esFactura, "${fichero.name} is not an invoice")
                return@forEach
            }
            val obtenido = mapOf("emisorNif" to p.emisorNif, "numero" to p.numero, "fechaEmision" to p.fechaEmision, "total" to p.total)
            campos.forEach { campo ->
                val esperadoValor = esperado[campo]?.takeIf { !it.isNull }?.asText()
                val obtenidoValor = obtenido[campo]
                evaluados++
                when {
                    esperadoValor == null && obtenidoValor != null -> inventados += "${fichero.name}.$campo"
                    esperadoValor != null && igual(campo, esperadoValor, obtenidoValor) -> aciertos++
                }
            }
        }

        val precision = aciertos.toDouble() / evaluados
        val p95 = tiempos.sorted()[((tiempos.size - 1) * 0.95).toInt()]
        val coste = tokensEntrada / 1e6 * 4.0 + tokensSalida / 1e6 * 20.0
        println("Precision: %.1f%% (%d/%d) | p95 %d ms | %d tokens de entrada, %d de salida | %.4f USD (%.4f por factura, precios de Opus 5.5)"
            .format(precision * 100, aciertos, evaluados, p95, tokensEntrada, tokensSalida, coste, coste / ficheros.size))

        assertEquals(emptyList(), inventados, "FR-004: what cannot be read must stay empty")
        assertTrue(precision >= 0.90, "SC-002: %.1f%% of required fields right".format(precision * 100))
        assertTrue(p95 < 30_000, "SC-001: 95% under 30 s, p95 = $p95 ms")
    }

    private fun igual(campo: String, esperado: String, obtenido: String?): Boolean = when (campo) {
        "total" -> obtenido != null && runCatching { BigDecimal(esperado).compareTo(BigDecimal(obtenido)) == 0 }.getOrDefault(false)
        "emisorNif" -> obtenido != null && esperado.uppercase().filter(Char::isLetterOrDigit).removePrefix("ES") ==
            obtenido.uppercase().filter(Char::isLetterOrDigit).removePrefix("ES")
        else -> esperado == obtenido
    }
}
