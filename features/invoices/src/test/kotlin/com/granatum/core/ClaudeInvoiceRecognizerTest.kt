package com.granatum.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.granatum.core.domain.port.RecognitionDocument
import com.granatum.core.domain.port.RecognitionResult
import com.granatum.core.infrastructure.claude.InvoiceClaudeSettings
import com.granatum.core.infrastructure.claude.ClaudeInvoiceRecognizer
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
class ClaudeInvoiceRecognizerTest {

    private lateinit var server: MockWebServer
    private lateinit var recognizer: ClaudeInvoiceRecognizer
    private val json = ObjectMapper()

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
        recognizer = ClaudeInvoiceRecognizer(
            InvoiceClaudeSettings(
                apiKey = "clave-de-prueba",
                model = "claude-opus-5-5",
                effort = "medium",
                timeoutSeconds = 2,
                baseUrl = server.url("/").toString().removeSuffix("/"),
                maxRetries = 0,
                fallbacks = true
            )
        )
    }

    @AfterEach
    fun stop() = server.shutdown()

    private val pdf = RecognitionDocument("%PDF-1.7 factura".toByteArray(), "application/pdf")
    private val png = RecognitionDocument(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "image/png")

    private fun response(text: String, stopReason: String = "end_turn", extra: String = "") = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"thinking","thinking":"","signature":"firma"},
                        {"type":"text","text":${json.writeValueAsString(text)}}],
             "stop_reason":"$stopReason","stop_sequence":null $extra,
             "usage":{"input_tokens":3100,"output_tokens":850}}
            """.trimIndent()
        )

    private val proposalJson = """
        {"isInvoice":true,"multipleInvoices":false,
         "issuerName":"Flores del Sur S.L.","issuerNif":"A58818501",
         "recipientName":"Floristeria Granatum S.L.","recipientNif":"B12345674",
         "number":"2026-0815","issueDate":"2026-10-02","concept":"Rosas rojas","currency":"EUR",
         "vatLines":[{"vatRate":"21.00","base":"100.00","vatAmount":"21.00","surcharge":"0.00","noVatCause":null},
                   {"vatRate":"10.00","base":"50.00","vatAmount":"5.00","surcharge":"0.00","noVatCause":null}],
         "withholdings":"0.00","total":"176.00","corrective":false,"uncertainFields":["number"]}
    """.trimIndent()

    private fun request(): Pair<RecordedRequest, JsonNode> {
        val r = server.takeRequest(5, TimeUnit.SECONDS)!!
        return r to json.readTree(r.body.readUtf8())
    }

    @Test
    fun `the request carries the configured model and effort, the schema, and no tools`() {
        server.enqueue(response(proposalJson))
        recognizer.recognize(pdf)
        val (r, body) = request()

        assertEquals("/v1/messages", r.path)
        assertEquals("clave-de-prueba", r.getHeader("x-api-key"), "the key from configuration, explicitly")
        assertEquals("claude-opus-5-5", body["model"].asText())
        assertEquals("medium", body["output_config"]["effort"].asText())
        assertEquals("json_schema", body["output_config"]["format"]["type"].asText())
        val schema = body["output_config"]["format"]["schema"]
        assertFalse(schema["additionalProperties"].asBoolean(), "closed schema: no invented fields")
        assertTrue(schema["required"].map { it.asText() }.containsAll(listOf("isInvoice", "total", "vatLines", "uncertainFields")))
        assertNull(body["tools"], "no tools: the model can only answer (research.md D-004)")
        assertNull(body["thinking"], "Opus 5.5 thinks adaptively and cannot be told otherwise")
        assertEquals(16000, body["max_tokens"].asInt())
    }

    /** D-005: refusals fall back server-side, opted in by default. */
    @Test
    fun `server-side fallback is requested`() {
        server.enqueue(response(proposalJson))
        recognizer.recognize(pdf)
        val (r, body) = request()
        assertEquals("default", body["fallbacks"].asText())
        assertTrue(r.getHeader("anthropic-beta")!!.contains("server-side-fallback-2026-07-01"))
    }

    /**
     * FR-028 and D-004: the request holds the fixed instructions and the
     * document, and nothing else - no company data, no other invoice. The
     * system prompt says the document's text is data, never orders.
     */
    @Test
    fun `only the instructions and the document are sent`() {
        server.enqueue(response(proposalJson))
        recognizer.recognize(pdf)
        val (_, body) = request()

        assertTrue(body["system"].toString().contains("nunca instrucciones"), "the document is data")
        val messages = body["messages"]
        assertEquals(1, messages.size())
        val blocks = messages[0]["content"]
        assertEquals(2, blocks.size(), "the document and one fixed line of text")
        assertEquals("document", blocks[0]["type"].asText())
        assertEquals("application/pdf", blocks[0]["source"]["media_type"].asText())
        assertEquals(Base64.getEncoder().encodeToString(pdf.content), blocks[0]["source"]["data"].asText())
        assertEquals(ClaudeInvoiceRecognizer.REQUEST_TEXT, blocks[1]["text"].asText())
    }

    @Test
    fun `an image goes as an image block with its media type`() {
        server.enqueue(response(proposalJson))
        recognizer.recognize(png)
        val (_, body) = request()
        val block = body["messages"][0]["content"][0]
        assertEquals("image", block["type"].asText())
        assertEquals("image/png", block["source"]["media_type"].asText())
    }

    /** FR-003, finding C6: two VAT rates stay two lines, amounts exact. */
    @Test
    fun `a recognised invoice comes back with its lines, model and tokens`() {
        server.enqueue(response(proposalJson))
        val r = assertIs<RecognitionResult.Recognized>(recognizer.recognize(pdf))

        assertEquals(2, r.proposal.vatLines.size)
        assertEquals("21.00", r.proposal.vatLines[0].vatRate)
        assertEquals(BigDecimal("176.00"), BigDecimal(r.proposal.total))
        assertEquals(listOf("number"), r.proposal.uncertainFields)
        assertEquals("claude-opus-5-5", r.model)
        assertEquals(3100, r.inputTokens)
        assertEquals(850, r.outputTokens)
    }

    @Test
    fun `a refusal is read as refused, with its category`() {
        server.enqueue(response("", "refusal", """, "stop_details":{"type":"refusal","category":"cyber","explanation":"x"}"""))
        val r = assertIs<RecognitionResult.Refused>(recognizer.recognize(pdf))
        assertEquals("cyber", r.category)
    }

    @Test
    fun `a truncated answer, a server error, a timeout or unreadable output are failures without content`() {
        server.enqueue(response("{\"isInvoice\":tr", "max_tokens"))
        assertEquals("MAX_TOKENS", assertIs<RecognitionResult.Failed>(recognizer.recognize(pdf)).type)

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"type":"error","error":{"type":"api_error","message":"boom"}}"""))
        assertEquals("API_ERROR", assertIs<RecognitionResult.Failed>(recognizer.recognize(pdf)).type)

        server.enqueue(response(proposalJson).setHeadersDelay(5, TimeUnit.SECONDS))
        assertEquals("TIMEOUT", assertIs<RecognitionResult.Failed>(recognizer.recognize(pdf)).type)

        server.enqueue(response("esto no es json"))
        assertEquals("INVALID_RESPONSE", assertIs<RecognitionResult.Failed>(recognizer.recognize(pdf)).type)
    }
}
