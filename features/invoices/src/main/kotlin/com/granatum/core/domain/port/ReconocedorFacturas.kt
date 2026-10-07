package com.granatum.core.domain.port

import com.granatum.core.domain.model.PropuestaReconocida

/** What is actually sent to be recognised: the original, or a reduced copy of it. */
class DocumentoParaEnviar(val contenido: ByteArray, val mediaType: String) {
    override fun toString(): String = "DocumentoParaEnviar(mediaType=$mediaType, bytes=${contenido.size})"
}

/**
 * Reads an invoice and proposes its data (FR-003). An interface so that every
 * test of the suite uses a double and none calls the real API (research.md
 * D-019), and so that the model or the provider can change without touching
 * the rest.
 *
 * Implementations must be called **outside any transaction**: a call can take
 * tens of seconds, and holding a database connection meanwhile is how features
 * 002 and 003 exhausted the pool (D-005).
 */
interface ReconocedorFacturas {

    /** False in manual mode, when there is no API key (D-006). */
    val activo: Boolean

    fun reconocer(documento: DocumentoParaEnviar): ResultadoReconocimiento
}

/** The outcome of one attempt, with what it cost (D-009). */
sealed interface ResultadoReconocimiento {
    val modelo: String
    val tokensEntrada: Int?
    val tokensSalida: Int?

    data class Reconocida(
        val propuesta: PropuestaReconocida,
        override val modelo: String,
        override val tokensEntrada: Int?,
        override val tokensSalida: Int?
    ) : ResultadoReconocimiento

    /** The model declined (`stop_reason: refusal`), with its category if any. */
    data class Rechazada(
        val categoria: String?,
        override val modelo: String,
        override val tokensEntrada: Int?,
        override val tokensSalida: Int?
    ) : ResultadoReconocimiento

    /**
     * Anything else that went wrong. [tipo] names the failure and never carries
     * anything from the document (FR-027).
     */
    data class Fallida(
        val tipo: String,
        override val modelo: String,
        override val tokensEntrada: Int? = null,
        override val tokensSalida: Int? = null
    ) : ResultadoReconocimiento
}
