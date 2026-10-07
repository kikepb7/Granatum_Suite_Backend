package com.granatum.core.validation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spanish tax identifiers: DNI, NIE (people) and CIF (legal entities).
 *
 * The DNI and NIE cases come from timetracking's `DocumentoIdentidadValidatorTest`,
 * which still runs unchanged against the employee form. CIF is new with
 * feature 004: invoices are issued by companies far more often than by people.
 */
class NifValidatorTest {

    @Test
    fun `DNI and NIE behave exactly as in timetracking`() {
        listOf("12345678Z", "00000000T", "99999999R", "X1234567L", "Y1234567X", "Z1234567R").forEach {
            assertTrue(NifValidator.esDniONieValido(it), it)
        }
        listOf("12345678A", "00000000A", "X1234567A", "Y1234567L", "", "1234567Z", "123456789Z", "12345678", "ABCDEFGHZ", "A1234567L").forEach {
            assertFalse(NifValidator.esDniONieValido(it), it)
        }
    }

    /**
     * Seven digits; the odd positions are doubled and their digits added, the
     * even ones added as they are, and the control is what takes the sum to the
     * next ten - as a digit, or as the letter at that index of `JABCDEFGHI`.
     */
    @Test
    fun `a CIF with the right control is valid`() {
        assertTrue(NifValidator.esCifValido("B12345674"), "B takes a digit control")
        assertTrue(NifValidator.esCifValido("A58818501"))
        assertTrue(NifValidator.esCifValido("Q1234567D"), "Q takes a letter control: 4 -> D")
        assertTrue(NifValidator.esCifValido("G12345674"), "G accepts the digit...")
        assertTrue(NifValidator.esCifValido("G1234567D"), "...and the letter")
    }

    @Test
    fun `a CIF with the wrong control, or the wrong kind of control, is invalid`() {
        assertFalse(NifValidator.esCifValido("B12345675"), "wrong digit")
        assertFalse(NifValidator.esCifValido("B1234567D"), "B must end in a digit")
        assertFalse(NifValidator.esCifValido("Q12345674"), "Q must end in a letter")
        assertFalse(NifValidator.esCifValido("I12345674"), "I is not an entity letter")
        assertFalse(NifValidator.esCifValido("B1234567"), "too short")
        assertFalse(NifValidator.esCifValido("12345678Z"), "a DNI is not a CIF")
    }

    @Test
    fun `any of the three is a valid NIF`() {
        assertTrue(NifValidator.esValido("12345678Z"))
        assertTrue(NifValidator.esValido("X1234567L"))
        assertTrue(NifValidator.esValido("B12345674"))
        assertFalse(NifValidator.esValido("B12345675"))
    }

    /** The document normalisation must not change: timetracking's uniqueness depends on it. */
    @Test
    fun `document normalisation upper-cases and drops spaces and hyphens, nothing else`() {
        assertEquals("12345678Z", NifValidator.normalizar("12345678 - z"))
        assertEquals("ES12345678Z", NifValidator.normalizar("es 12345678-z"), "no ES stripping here")
    }

    /** On invoices the same company appears as `B12345674`, `B-12345674` or `ESB12345674` (VAT number). */
    @Test
    fun `tax normalisation also drops the ES country prefix`() {
        listOf("B12345674", "b-12345674", " B 1234 5674 ", "ESB12345674", "es-B12345674").forEach {
            assertEquals("B12345674", NifValidator.normalizarFiscal(it), it)
        }
        assertEquals("X1234567L", NifValidator.normalizarFiscal("ESX1234567L"))
        assertTrue(NifValidator.esValido(NifValidator.normalizarFiscal("ESB12345674")))
    }
}
