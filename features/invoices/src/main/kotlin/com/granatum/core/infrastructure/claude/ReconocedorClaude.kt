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
import com.granatum.core.domain.model.PropuestaReconocida
import com.granatum.core.domain.port.DocumentoParaEnviar
import com.granatum.core.domain.port.ReconocedorFacturas
import com.granatum.core.domain.port.ResultadoReconocimiento
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
 * - **The key is passed explicitly** from [ConfiguracionClaude]. The client is
 *   never built with `fromEnv()` or without a key, because then the SDK would
 *   look for credentials by itself (ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN, the
 *   `ant auth login` profile on disk) and a context configured "without key"
 *   could still spend money (finding S1).
 * - No `thinking` parameter: on Opus 5.5 thinking is adaptive and cannot be
 *   disabled; depth is set with the effort.
 *
 * Errors become a [ResultadoReconocimiento.Fallida] naming the kind of failure,
 * never quoting the document or the API's message (FR-027).
 */
class ReconocedorClaude(private val config: ConfiguracionClaude) : ReconocedorFacturas {

    companion object {
        /** The one line of text that goes with the document. */
        const val PETICION = "Extrae los datos de esta factura con el esquema indicado."
        private const val MAX_TOKENS = 16_000L
        private const val BETA_FALLBACK = "server-side-fallback-2026-07-01"

        private val INSTRUCCIONES: String =
            ReconocedorClaude::class.java.getResource("/prompts/reconocimiento-factura.md")!!.readText()

        private fun nulable(tipo: String) = mapOf("type" to listOf(tipo, "null"))

        /** Closed schema: every field required, nulls allowed where a value may be unreadable. */
        val ESQUEMA: Map<String, Any> = run {
            val linea = mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "properties" to mapOf(
                    "tipoIva" to nulable("string"),
                    "base" to nulable("string"),
                    "cuota" to nulable("string"),
                    "recargo" to nulable("string"),
                    "causaSinCuota" to nulable("string")
                ),
                "required" to listOf("tipoIva", "base", "cuota", "recargo", "causaSinCuota")
            )
            val propiedades = linkedMapOf(
                "esFactura" to mapOf("type" to "boolean"),
                "variasFacturas" to mapOf("type" to "boolean"),
                "emisorNombre" to nulable("string"),
                "emisorNif" to nulable("string"),
                "destinatarioNombre" to nulable("string"),
                "destinatarioNif" to nulable("string"),
                "numero" to nulable("string"),
                "fechaEmision" to nulable("string"),
                "concepto" to nulable("string"),
                "moneda" to nulable("string"),
                "lineas" to mapOf("type" to "array", "items" to linea),
                "retenciones" to nulable("string"),
                "total" to nulable("string"),
                "rectificativa" to nulable("boolean"),
                "camposDudosos" to mapOf("type" to "array", "items" to mapOf("type" to "string"))
            )
            mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "properties" to propiedades,
                "required" to propiedades.keys.toList()
            )
        }
    }

    /** Its own mapper: the proposal is a wire format of this integration, not the API's (cf. timetracking D-006). */
    private val json = ObjectMapper().registerKotlinModule()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    private val cliente: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(config.clave())
        .apply { if (config.baseUrl.isNotBlank()) baseUrl(config.baseUrl) }
        .timeout(Duration.ofSeconds(config.timeoutSegundos))
        .maxRetries(config.maxReintentos)
        .build()

    override val activo: Boolean = true

    override fun reconocer(documento: DocumentoParaEnviar): ResultadoReconocimiento {
        val respuesta = try {
            cliente.messages().create(peticion(documento))
        } catch (e: AnthropicServiceException) {
            return ResultadoReconocimiento.Fallida("ERROR_API", config.modelo)
        } catch (e: AnthropicIoException) {
            val tipo = if (generateSequence(e as Throwable) { it.cause }.any { it is InterruptedIOException }) "TIMEOUT" else "ERROR_RED"
            return ResultadoReconocimiento.Fallida(tipo, config.modelo)
        }
        return interpretar(respuesta)
    }

    private fun peticion(documento: DocumentoParaEnviar): MessageCreateParams {
        val datos = Base64.getEncoder().encodeToString(documento.contenido)
        val bloqueDocumento = if (documento.mediaType == "application/pdf") {
            ContentBlockParam.ofDocument(
                DocumentBlockParam.builder().source(Base64PdfSource.builder().data(datos).build()).build()
            )
        } else {
            ContentBlockParam.ofImage(
                ImageBlockParam.builder().source(
                    Base64ImageSource.builder()
                        .data(datos)
                        .mediaType(Base64ImageSource.MediaType.of(documento.mediaType))
                        .build()
                ).build()
            )
        }

        val esquema = JsonOutputFormat.Schema.builder()
            .apply { ESQUEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }
            .build()

        return MessageCreateParams.builder()
            .model(config.modelo)
            .maxTokens(MAX_TOKENS)
            .system(INSTRUCCIONES)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.of(config.esfuerzo))
                    .format(JsonOutputFormat.builder().schema(esquema).build())
                    .build()
            )
            .addUserMessageOfBlockParams(listOf(bloqueDocumento, ContentBlockParam.ofText(PETICION)))
            .apply {
                if (config.fallbacks) {
                    putAdditionalHeader("anthropic-beta", BETA_FALLBACK)
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()
    }

    private fun interpretar(respuesta: Message): ResultadoReconocimiento {
        val modelo = respuesta.model().asString()
        val entrada = respuesta.usage().inputTokens().toInt()
        val salida = respuesta.usage().outputTokens().toInt()

        when (respuesta.stopReason().orElse(null)) {
            StopReason.REFUSAL -> return ResultadoReconocimiento.Rechazada(
                respuesta.stopDetails().flatMap { it.category() }.map { it.asString() }.orElse(null),
                modelo, entrada, salida
            )
            StopReason.END_TURN -> Unit
            StopReason.MAX_TOKENS -> return ResultadoReconocimiento.Fallida("MAX_TOKENS", modelo, entrada, salida)
            else -> return ResultadoReconocimiento.Fallida("PARADA_INESPERADA", modelo, entrada, salida)
        }

        val texto = respuesta.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        val propuesta = try {
            json.readValue(texto, PropuestaReconocida::class.java)
        } catch (e: Exception) {
            return ResultadoReconocimiento.Fallida("RESPUESTA_INVALIDA", modelo, entrada, salida)
        }
        return ResultadoReconocimiento.Reconocida(propuesta, modelo, entrada, salida)
    }
}

/** Manual mode, without an API key: nothing is ever sent (research.md D-006). */
class ReconocedorDeshabilitado : ReconocedorFacturas {
    override val activo: Boolean = false
    override fun reconocer(documento: DocumentoParaEnviar): ResultadoReconocimiento =
        throw IllegalStateException("reconocimiento deshabilitado: sin clave de API")
}
