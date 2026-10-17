package com.granatum.core.domain.port

import com.granatum.core.domain.model.RecognizedProposal

/** What is actually sent to be recognised: the original, or a reduced copy of it. */
class RecognitionDocument(val content: ByteArray, val mediaType: String) {
    override fun toString(): String = "RecognitionDocument(mediaType=$mediaType, bytes=${content.size})"
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
interface InvoiceRecognizer {

    /** False in manual mode, when there is no API key (D-006). */
    val active: Boolean

    fun recognize(document: RecognitionDocument): RecognitionResult
}

/** The outcome of one attempt, with what it cost (D-009). */
sealed interface RecognitionResult {
    val model: String
    val inputTokens: Int?
    val outputTokens: Int?

    data class Recognized(
        val proposal: RecognizedProposal,
        override val model: String,
        override val inputTokens: Int?,
        override val outputTokens: Int?
    ) : RecognitionResult

    /** The model declined (`stop_reason: refusal`), with its category if any. */
    data class Refused(
        val category: String?,
        override val model: String,
        override val inputTokens: Int?,
        override val outputTokens: Int?
    ) : RecognitionResult

    /**
     * Anything else that went wrong. [type] names the failure and never carries
     * anything from the document (FR-027).
     */
    data class Failed(
        val type: String,
        override val model: String,
        override val inputTokens: Int? = null,
        override val outputTokens: Int? = null
    ) : RecognitionResult
}
