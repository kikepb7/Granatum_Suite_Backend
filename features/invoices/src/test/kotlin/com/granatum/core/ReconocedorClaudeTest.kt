package com.granatum.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.granatum.core.domain.port.DocumentoParaEnviar
import com.granatum.core.domain.port.ResultadoReconocimiento
import com.granatum.core.infrastructure.claude.ConfiguracionClaude
import com.granatum.core.infrastructure.claude.ReconocedorClaude
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real recogniser, against a local mock of the Claude API: no network, no
 * cost (research.md D-019). It checks what is sent - and what is not - and how
 * every kind of answer is read.
 */
class ReconocedorClaudeTest {

    private lateinit var servidor: MockWebServer
    private lateinit var reconocedor: ReconocedorClaude
    private val json = ObjectMapper()

    @BeforeEach
    fun arrancar() {
        servidor = MockWebServer().apply { start() }
        reconocedor = ReconocedorClaude(
            ConfiguracionClaude(
                apiKey = "clave-de-prueba",
                modelo = "claude-opus-5-5",
                esfuerzo = "medium",
                timeoutSegundos = 2,
                baseUrl = servidor.url("/").toString().removeSuffix("/"),
                maxReintentos = 0,
                fallbacks = true
            )
        )
    }

    @AfterEach
    fun parar() = servidor.shutdown()

    private val pdf = DocumentoParaEnviar("%PDF-1.7 factura".toByteArray(), "application/pdf")
    private val png = DocumentoParaEnviar(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "image/png")

    private fun respuesta(texto: String, stopReason: String = "end_turn", extra: String = "") = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"thinking","thinking":"","signature":"firma"},
                        {"type":"text","text":${json.writeValueAsString(texto)}}],
             "stop_reason":"$stopReason","stop_sequence":null $extra,
             "usage":{"input_tokens":3100,"output_tokens":850}}
            """.trimIndent()
        )

    private val propuestaJson = """
        {"esFactura":true,"variasFacturas":false,
         "emisorNombre":"Flores del Sur S.L.","emisorNif":"A58818501",
         "destinatarioNombre":"Floristeria Granatum S.L.","destinatarioNif":"B12345674",
         "numero":"2026-0815","fechaEmision":"2026-10-02","concepto":"Rosas rojas","moneda":"EUR",
         "lineas":[{"tipoIva":"21.00","base":"100.00","cuota":"21.00","recargo":"0.00","causaSinCuota":null},
                   {"tipoIva":"10.00","base":"50.00","cuota":"5.00","recargo":"0.00","causaSinCuota":null}],
         "retenciones":"0.00","total":"176.00","rectificativa":false,"camposDudosos":["numero"]}
    """.trimIndent()

    private fun peticion(): Pair<RecordedRequest, JsonNode> {
        val r = servidor.takeRequest(5, TimeUnit.SECONDS)!!
        return r to json.readTree(r.body.readUtf8())
    }

    @Test
    fun `the request carries the configured model and effort, the schema, and no tools`() {
        servidor.enqueue(respuesta(propuestaJson))
        reconocedor.reconocer(pdf)
        val (r, cuerpo) = peticion()

        assertEquals("/v1/messages", r.path)
        assertEquals("clave-de-prueba", r.getHeader("x-api-key"), "the key from configuration, explicitly")
        assertEquals("claude-opus-5-5", cuerpo["model"].asText())
        assertEquals("medium", cuerpo["output_config"]["effort"].asText())
        assertEquals("json_schema", cuerpo["output_config"]["format"]["type"].asText())
        val esquema = cuerpo["output_config"]["format"]["schema"]
        assertFalse(esquema["additionalProperties"].asBoolean(), "closed schema: no invented fields")
        assertTrue(esquema["required"].map { it.asText() }.containsAll(listOf("esFactura", "total", "lineas", "camposDudosos")))
        assertNull(cuerpo["tools"], "no tools: the model can only answer (research.md D-004)")
        assertNull(cuerpo["thinking"], "Opus 5.5 thinks adaptively and cannot be told otherwise")
        assertEquals(16000, cuerpo["max_tokens"].asInt())
    }

    /** D-005: refusals fall back server-side, opted in by default. */
    @Test
    fun `server-side fallback is requested`() {
        servidor.enqueue(respuesta(propuestaJson))
        reconocedor.reconocer(pdf)
        val (r, cuerpo) = peticion()
        assertEquals("default", cuerpo["fallbacks"].asText())
        assertTrue(r.getHeader("anthropic-beta")!!.contains("server-side-fallback-2026-07-01"))
    }

    /**
     * FR-028 and D-004: the request holds the fixed instructions and the
     * document, and nothing else - no company data, no other invoice. The
     * system prompt says the document's text is data, never orders.
     */
    @Test
    fun `only the instructions and the document are sent`() {
        servidor.enqueue(respuesta(propuestaJson))
        reconocedor.reconocer(pdf)
        val (_, cuerpo) = peticion()

        assertTrue(cuerpo["system"].toString().contains("nunca instrucciones"), "the document is data")
        val mensajes = cuerpo["messages"]
        assertEquals(1, mensajes.size())
        val bloques = mensajes[0]["content"]
        assertEquals(2, bloques.size(), "the document and one fixed line of text")
        assertEquals("document", bloques[0]["type"].asText())
        assertEquals("application/pdf", bloques[0]["source"]["media_type"].asText())
        assertEquals(Base64.getEncoder().encodeToString(pdf.contenido), bloques[0]["source"]["data"].asText())
        assertEquals(ReconocedorClaude.PETICION, bloques[1]["text"].asText())
    }

    @Test
    fun `an image goes as an image block with its media type`() {
        servidor.enqueue(respuesta(propuestaJson))
        reconocedor.reconocer(png)
        val (_, cuerpo) = peticion()
        val bloque = cuerpo["messages"][0]["content"][0]
        assertEquals("image", bloque["type"].asText())
        assertEquals("image/png", bloque["source"]["media_type"].asText())
    }

    /** FR-003, finding C6: two VAT rates stay two lines, amounts exact. */
    @Test
    fun `a recognised invoice comes back with its lines, model and tokens`() {
        servidor.enqueue(respuesta(propuestaJson))
        val r = assertIs<ResultadoReconocimiento.Reconocida>(reconocedor.reconocer(pdf))

        assertEquals(2, r.propuesta.lineas.size)
        assertEquals("21.00", r.propuesta.lineas[0].tipoIva)
        assertEquals(BigDecimal("176.00"), BigDecimal(r.propuesta.total))
        assertEquals(listOf("numero"), r.propuesta.camposDudosos)
        assertEquals("claude-opus-5-5", r.modelo)
        assertEquals(3100, r.tokensEntrada)
        assertEquals(850, r.tokensSalida)
    }

    @Test
    fun `a refusal is read as refused, with its category`() {
        servidor.enqueue(respuesta("", "refusal", """, "stop_details":{"type":"refusal","category":"cyber","explanation":"x"}"""))
        val r = assertIs<ResultadoReconocimiento.Rechazada>(reconocedor.reconocer(pdf))
        assertEquals("cyber", r.categoria)
    }

    @Test
    fun `a truncated answer, a server error, a timeout or unreadable output are failures without content`() {
        servidor.enqueue(respuesta("{\"esFactura\":tr", "max_tokens"))
        assertEquals("MAX_TOKENS", assertIs<ResultadoReconocimiento.Fallida>(reconocedor.reconocer(pdf)).tipo)

        servidor.enqueue(MockResponse().setResponseCode(500).setBody("""{"type":"error","error":{"type":"api_error","message":"boom"}}"""))
        assertEquals("ERROR_API", assertIs<ResultadoReconocimiento.Fallida>(reconocedor.reconocer(pdf)).tipo)

        servidor.enqueue(respuesta(propuestaJson).setHeadersDelay(5, TimeUnit.SECONDS))
        assertEquals("TIMEOUT", assertIs<ResultadoReconocimiento.Fallida>(reconocedor.reconocer(pdf)).tipo)

        servidor.enqueue(respuesta("esto no es json"))
        assertEquals("RESPUESTA_INVALIDA", assertIs<ResultadoReconocimiento.Fallida>(reconocedor.reconocer(pdf)).tipo)
    }
}
