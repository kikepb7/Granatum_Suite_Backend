package com.granatum.core

import com.granatum.core.infrastructure.crypto.BootstrapCodeCheck
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Feature 006, FR-010: a guessable bootstrap code refuses to start. Pure: the
 * rule is a length, and what matters is that the application stops rather than
 * how Spring wires it.
 */
class BootstrapCodeCheckTest {

    @Test
    fun `no code means bootstrap disabled, and that starts`() {
        BootstrapCodeCheck("").verify()
        BootstrapCodeCheck("   ").verify()
    }

    @Test
    fun `24 characters is accepted`() {
        BootstrapCodeCheck("a".repeat(24)).verify()
    }

    @Test
    fun `23 characters refuses to start, naming the variable and never the value`() {
        val code = "granatum-corto-2026-abc"
        val error = assertFailsWith<IllegalStateException> { BootstrapCodeCheck(code).verify() }

        assertTrue(error.message!!.contains("AUTH_BOOTSTRAP_CODE"), error.message)
        assertFalse(error.message!!.contains(code), "the secret must not end up in a startup log")
    }
}
