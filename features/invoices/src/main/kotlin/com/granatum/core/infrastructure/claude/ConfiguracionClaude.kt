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
class ConfiguracionClaude(
    @param:Value("\${invoices.claude.api-key:}") private val apiKey: String,
    @param:Value("\${invoices.claude.model:claude-opus-5-5}") val modelo: String,
    @param:Value("\${invoices.claude.effort:medium}") val esfuerzo: String,
    @param:Value("\${invoices.reconocimiento.timeout-segundos:120}") val timeoutSegundos: Long
) {
    val activo: Boolean get() = apiKey.isNotBlank()

    /** The key, for the one place that builds the client. Never logged. */
    fun clave(): String {
        check(activo) { "sin clave de API: modo manual" }
        return apiKey
    }

    override fun toString(): String = "ConfiguracionClaude(activo=$activo, modelo=$modelo)"
}
