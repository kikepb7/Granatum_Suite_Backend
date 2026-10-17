package com.granatum.core.infrastructure.claude

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Where the recognition settings come from - and the **only** place the API key
 * comes from (research.md D-006, finding S1).
 *
 * The key is `invoices.claude.api-key`, mapped from ANTHROPIC_API_KEY with an
 * empty default. Empty means manual mode: nothing is sent to Claude. The
 * Anthropic SDK is never left to find credentials by itself (its zero-argument
 * client reads ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN and the `ant auth login`
 * profile on disk), because then a test or an environment configured "without
 * key" could still authenticate and spend money.
 */
@Component
class InvoiceClaudeSettings(
    @param:Value("\${invoices.claude.api-key:}") private val apiKey: String,
    @param:Value("\${invoices.claude.model:claude-opus-5-5}") val model: String,
    @param:Value("\${invoices.claude.effort:medium}") val effort: String,
    @param:Value("\${invoices.recognition.timeout-seconds:120}") val timeoutSeconds: Long,
    /** Empty = the SDK's default. Set only by ClaudeInvoiceRecognizerTest, to its local mock server. */
    @param:Value("\${invoices.claude.base-url:}") val baseUrl: String = "",
    @param:Value("\${invoices.claude.max-retries:2}") val maxRetries: Int = 2,
    /**
     * Server-side fallback on refusal (research.md D-005). On by default; a
     * switch in case it proves incompatible with structured outputs, which
     * could not be checked without API access (finding U1).
     */
    @param:Value("\${invoices.claude.fallbacks:true}") val fallbacks: Boolean = true
) {
    val active: Boolean get() = apiKey.isNotBlank()

    /** The key, for the one place that builds the client. Never logged. */
    fun key(): String {
        check(active) { "sin clave de API: modo manual" }
        return apiKey
    }

    override fun toString(): String = "InvoiceClaudeSettings(active=$active, model=$model)"
}
