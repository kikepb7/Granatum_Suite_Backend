package com.granatum.core

import com.granatum.core.api.validation.DocumentoIdentidadValidator
import com.granatum.core.api.validation.DocumentoIdentidadValidator.Companion.esValido
import com.granatum.core.api.validation.DocumentoIdentidadValidator.Companion.normalizar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit tests of the DNI/NIE checksum and normalisation.
 *
 * The check letters below are computed from the real algorithm (number mod 23
 * indexed into TRWAGMYFPDXBNJZSQVHLCKE), not copied from anywhere: a test built
 * from invented documents would pass against a broken implementation.
 */
class DocumentoIdentidadValidatorTest {

    @Test
    fun `a DNI with a correct check letter is accepted`() {
        // 12345678 mod 23 = 14 -> 'Z'
        assertTrue(esValido("12345678Z"))
        // 00000000 mod 23 = 0 -> 'T'
        assertTrue(esValido("00000000T"))
        // 99999999 mod 23 = 7 -> 'R'
        assertTrue(esValido("99999999R"))
    }

    @Test
    fun `a DNI with a wrong check letter is rejected`() {
        assertFalse(esValido("12345678A"), "the correct letter for 12345678 is Z")
        assertFalse(esValido("00000000A"))
    }

    @Test
    fun `a NIE is accepted, with its prefix mapped before the checksum`() {
        // X -> 0, so X1234567 is checked as 01234567: mod 23 = 14 -> 'L'
        assertTrue(esValido("X1234567L"))
        // Y -> 1, so Y1234567 is checked as 11234567: mod 23 = 9 -> 'X'
        assertTrue(esValido("Y1234567X"))
        // Z -> 2, so Z1234567 is checked as 21234567: mod 23 = 1 -> 'R'
        assertTrue(esValido("Z1234567R"))
    }

    @Test
    fun `a NIE with a wrong check letter is rejected`() {
        assertFalse(esValido("X1234567A"))
        // The same digits with a different prefix must not share a check letter:
        // this is what proves the prefix is actually being mapped.
        assertFalse(esValido("Y1234567L"), "Y maps to 1, so the letter cannot be X1234567's")
    }

    @Test
    fun `malformed documents are rejected`() {
        assertFalse(esValido(""), "empty")
        assertFalse(esValido("1234567Z"), "seven digits")
        assertFalse(esValido("123456789Z"), "nine digits")
        assertFalse(esValido("12345678"), "no check letter")
        assertFalse(esValido("ABCDEFGHZ"), "not digits")
        assertFalse(esValido("A1234567L"), "A is not a NIE prefix")
    }

    /**
     * Normalisation is what stops FR-029 being bypassed by punctuation. Without
     * it the unique index would treat these as different people, which is the
     * same person registered twice.
     */
    @Test
    fun `the same document written differently normalises to one value`() {
        val esperado = "12345678Z"

        assertEquals(esperado, normalizar("12345678z"))
        assertEquals(esperado, normalizar("12345678-Z"))
        assertEquals(esperado, normalizar(" 12345678 Z "))
        assertEquals(esperado, normalizar("12345678 - z"))
    }

    @Test
    fun `a normalised variant validates just as the canonical form does`() {
        listOf("12345678z", "12345678-Z", " 12345678 Z ").forEach { variante ->
            assertTrue(
                esValido(normalizar(variante)),
                "$variante must validate once normalised"
            )
        }
    }

    @Test
    fun `the validator rejects null and blank`() {
        val validador = DocumentoIdentidadValidator()

        assertFalse(validador.isValid(null, null))
        assertFalse(validador.isValid("", null))
        assertFalse(validador.isValid("   ", null))
    }

    @Test
    fun `the validator accepts an unnormalised but valid document`() {
        val validador = DocumentoIdentidadValidator()

        assertTrue(
            validador.isValid("12345678-z", null),
            "validation normalises first, so punctuation is not a rejection reason"
        )
    }
}
