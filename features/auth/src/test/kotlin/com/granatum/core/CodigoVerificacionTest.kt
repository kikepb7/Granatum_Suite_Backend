package com.granatum.core

import com.granatum.core.domain.service.CodigoVerificacion
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The sign-up verification code (feature 005, FR-004, research.md D-001). */
class CodigoVerificacionTest {

    @Test
    fun `a code has 8 characters from an alphabet that reads aloud without ambiguity`() {
        repeat(500) {
            val codigo = CodigoVerificacion.generar()
            assertEquals(8, codigo.length)
            assertTrue(codigo.all { it in CodigoVerificacion.ALFABETO }, codigo)
        }
        listOf('0', 'O', '1', 'I').forEach {
            assertFalse(it in CodigoVerificacion.ALFABETO, "$it is confusable when read aloud")
        }
    }

    @Test
    fun `codes are not repeated in practice`() {
        val codigos = (1..2_000).map { CodigoVerificacion.generar() }.toSet()
        assertEquals(2_000, codigos.size)
    }

    @Test
    fun `the fingerprint is salted with the request id`() {
        val codigo = CodigoVerificacion.generar()
        val huella = CodigoVerificacion.huella(UUID.randomUUID(), codigo)

        assertEquals(64, huella.length)
        assertNotEquals(huella, CodigoVerificacion.huella(UUID.randomUUID(), codigo))
    }

    @Test
    fun `the right code matches, also typed in lowercase or with spaces`() {
        val id = UUID.randomUUID()
        val codigo = CodigoVerificacion.generar()
        val huella = CodigoVerificacion.huella(id, codigo)

        assertTrue(CodigoVerificacion.coincide(id, codigo, huella))
        assertTrue(CodigoVerificacion.coincide(id, " ${codigo.lowercase().chunked(4).joinToString(" ")} ", huella))
    }

    @Test
    fun `a wrong code, or the right code for another request, does not match`() {
        val id = UUID.randomUUID()
        val codigo = CodigoVerificacion.generar()
        val huella = CodigoVerificacion.huella(id, codigo)

        assertFalse(CodigoVerificacion.coincide(id, CodigoVerificacion.generar(), huella))
        assertFalse(CodigoVerificacion.coincide(UUID.randomUUID(), codigo, huella))
        assertFalse(CodigoVerificacion.coincide(id, "", huella))
    }
}
