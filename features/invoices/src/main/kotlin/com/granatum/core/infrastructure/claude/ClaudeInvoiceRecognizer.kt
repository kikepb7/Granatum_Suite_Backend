package com.granatum.core.infrastructure.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.Base64PdfSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.DocumentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.granatum.core.domain.model.RecognizedProposal
import com.granatum.core.domain.port.RecognitionDocument
import com.granatum.core.domain.port.InvoiceRecognizer
import com.granatum.core.domain.port.RecognitionResult
import java.io.InterruptedIOException
import java.time.Duration
import java.util.Base64

/**
 * Reads an invoice with Claude: **one** Messages API call, the document sent
 * as it is (PDF as a document block, images as image blocks), the answer
 * constrained to a closed JSON schema (research.md D-002, D-003).
 *
 * - **No tools**: the model can only answer, so nothing written on an invoice
 *   can make it do anything but fill in draft fields (D-004, FR-007).
 * - **Only the document and fixed instructions** are sent: no company data, no
 *   other invoice (FR-028).
 * - **The key is passed explicitly** from [InvoiceClaudeSettings]. The client is
 *   never built with `fromEnv()` or without a key, because then the SDK would
 *   look for credentials by itself (ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN, the
 *   `ant auth login` profile on disk) and a context configured "without key"
 *   could still spend money (finding S1).
 * - No `thinking` parameter: thinking is adaptive (Sonnet 5.5 by default) and
 *   its depth is set with the effort.
 *
 * Errors become a [RecognitionResult.Failed] naming the kind of failure,
 * never quoting the document or the API's message (FR-027).
 */
class ClaudeInvoiceRecognizer(private val config: InvoiceClaudeSettings) : InvoiceRecognizer {

    companion object {
        /** The one line of text that goes with the document. */
        const val REQUEST_TEXT = "Extrae los datos de esta factura con el esquema indicado."
        private const val MAX_TOKENS = 16_000L
        private const val BETA_FALLBACK = "server-side-fallback-2026-07-01"

        private val INSTRUCTIONS: String =
            ClaudeInvoiceRecognizer::class.java.getResource("/prompts/invoice-recognition.md")!!.readText()

        private fun nullable(type: String) = mapOf("type" to listOf(type, "null"))

        /** Closed schema: every field required, nulls allowed where a value may be unreadable. */
        val SCHEMA: Map<String, Any> = run {
            val line = mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "properties" to mapOf(
                    "vatRate" to nullable("string"),
                    "base" to nullable("string"),
                    "vatAmount" to nullable("string"),
                    "surcharge" to nullable("string"),
                    "noVatCause" to nullable("string")
                ),
                "required" to listOf("vatRate", "base", "vatAmount", "surcharge", "noVatCause")
            )
            val properties = linkedMapOf(
                "isInvoice" to mapOf("type" to "boolean"),
                "multipleInvoices" to mapOf("type" to "boolean"),
                "issuerName" to nullable("string"),
                "issuerNif" to nullable("string"),
                "recipientName" to nullable("string"),
                "recipientNif" to nullable("string"),
                "number" to nullable("string"),
                "issueDate" to nullable("string"),
                "concept" to nullable("string"),
                "currency" to nullable("string"),
                "vatLines" to mapOf("type" to "array", "items" to line),
                "withholdings" to nullable("string"),
                "total" to nullable("string"),
                "corrective" to nullable("boolean"),
                "uncertainFields" to mapOf("type" to "array", "items" to mapOf("type" to "string"))
            )
            mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "properties" to properties,
                "required" to properties.keys.toList()
            )
        }
    }

    /** Its own mapper: the proposal is a wire format of this integration, not the API's (cf. timetracking D-006). */
    private val json = ObjectMapper().registerKotlinModule()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(config.key())
        .apply { if (config.baseUrl.isNotBlank()) baseUrl(config.baseUrl) }
        .timeout(Duration.ofSeconds(config.timeoutSeconds))
        .maxRetries(config.maxRetries)
        .build()

    override val active: Boolean = true

    override fun recognize(document: RecognitionDocument): RecognitionResult {
        val response = try {
            client.messages().create(request(document))
        } catch (e: AnthropicServiceException) {
            return RecognitionResult.Failed("API_ERROR", config.model)
        } catch (e: AnthropicIoException) {
            val type = if (generateSequence(e as Throwable) { it.cause }.any { it is InterruptedIOException }) "TIMEOUT" else "NETWORK_ERROR"
            return RecognitionResult.Failed(type, config.model)
        }
        return interpret(response)
    }

    private fun request(document: RecognitionDocument): MessageCreateParams {
        val data = Base64.getEncoder().encodeToString(document.content)
        val documentBlock = if (document.mediaType == "application/pdf") {
            ContentBlockParam.ofDocument(
                DocumentBlockParam.builder().source(Base64PdfSource.builder().data(data).build()).build()
            )
        } else {
            ContentBlockParam.ofImage(
                ImageBlockParam.builder().source(
                    Base64ImageSource.builder()
                        .data(data)
                        .mediaType(Base64ImageSource.MediaType.of(document.mediaType))
                        .build()
                ).build()
            )
        }

        val schema = JsonOutputFormat.Schema.builder()
            .apply { SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }
            .build()

        return MessageCreateParams.builder()
            .model(config.model)
            .maxTokens(MAX_TOKENS)
            .system(INSTRUCTIONS)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.of(config.effort))
                    .format(JsonOutputFormat.builder().schema(schema).build())
                    .build()
            )
            .addUserMessageOfBlockParams(listOf(documentBlock, ContentBlockParam.ofText(REQUEST_TEXT)))
            .apply {
                if (config.fallbacks) {
                    putAdditionalHeader("anthropic-beta", BETA_FALLBACK)
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()
    }

    private fun interpret(response: Message): RecognitionResult {
        val model = response.model().asString()
        val input = response.usage().inputTokens().toInt()
        val output = response.usage().outputTokens().toInt()

        when (response.stopReason().orElse(null)) {
            StopReason.REFUSAL -> return RecognitionResult.Refused(
                response.stopDetails().flatMap { it.category() }.map { it.asString() }.orElse(null),
                model, input, output
            )
            StopReason.END_TURN -> Unit
            StopReason.MAX_TOKENS -> return RecognitionResult.Failed("MAX_TOKENS", model, input, output)
            else -> return RecognitionResult.Failed("UNEXPECTED_STOP", model, input, output)
        }

        val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        val proposal = try {
            json.readValue(text, RecognizedProposal::class.java)
        } catch (e: Exception) {
            return RecognitionResult.Failed("INVALID_RESPONSE", model, input, output)
        }
        return RecognitionResult.Recognized(proposal, model, input, output)
    }
}

/** Manual mode, without an API key: nothing is ever sent (research.md D-006). */
class DisabledInvoiceRecognizer : InvoiceRecognizer {
    override val active: Boolean = false
    override fun recognize(document: RecognitionDocument): RecognitionResult =
        throw IllegalStateException("reconocimiento deshabilitado: sin clave de API")
}
