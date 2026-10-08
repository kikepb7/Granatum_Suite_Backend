package com.granatum.core

import com.granatum.core.infrastructure.crypto.ComprobacionCodigoArranque
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Feature 006, FR-010: a guessable bootstrap code refuses to start. Pure: the
 * rule is a length, and what matters is that the application stops rather than
 * how Spring wires it.
 */
class ComprobacionCodigoArranqueTest {

    @Test
    fun `no code means bootstrap disabled, and that starts`() {
        ComprobacionCodigoArranque("").comprobar()
        ComprobacionCodigoArranque("   ").comprobar()
    }

    @Test
    fun `24 characters is accepted`() {
        ComprobacionCodigoArranque("a".repeat(24)).comprobar()
    }

    @Test
    fun `23 characters refuses to start, naming the variable and never the value`() {
        val codigo = "granatum-corto-2026-abc"
        val error = assertFailsWith<IllegalStateException> { ComprobacionCodigoArranque(codigo).comprobar() }

        assertTrue(error.message!!.contains("AUTH_CODIGO_ARRANQUE"), error.message)
        assertFalse(error.message!!.contains(codigo), "the secret must not end up in a startup log")
    }
}
