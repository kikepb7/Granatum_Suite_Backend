package com.granatum.core.validation

/**
 * Spanish tax identifiers, check character included: DNI and NIE for people,
 * CIF for legal entities.
 *
 * Moved here from timetracking's `DocumentoIdentidadValidator` when invoicing
 * (feature 004) needed the same check, plus CIF: two copies of the DNI rule
 * would drift (constitution principle I: what features share lives in common).
 */
object NifValidator {

    private const val DNI_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE"

    /** NIE prefixes map onto a leading digit before the checksum is computed. */
    private val NIE_PREFIXES = mapOf('X' to "0", 'Y' to "1", 'Z' to "2")

    private val DNI = Regex("^\\d{8}[A-Z]$")
    private val NIE = Regex("^[XYZ]\\d{7}[A-Z]$")
    private val CIF = Regex("^[ABCDEFGHJNPQRSUVW]\\d{7}[0-9A-J]$")

    private const val CIF_LETTERS = "JABCDEFGHI"
    /** Entities whose control is always a letter; [DIGIT_CONTROL_ENTITIES] always a digit; the rest, either. */
    private const val LETTER_CONTROL_ENTITIES = "NPQRSW"
    private const val DIGIT_CONTROL_ENTITIES = "ABEH"

    /**
     * Upper-cased, spaces and hyphens stripped - and nothing else. It is what
     * timetracking's uniqueness of identity documents relies on, so it must
     * not change: `12345678z` and `12345678-Z` are the same person.
     */
    fun normalize(value: String): String = value.uppercase().replace(" ", "").replace("-", "")

    /**
     * [normalize] plus the `ES` country prefix of a VAT number, which invoices
     * often carry: `ESB12345674` is the company `B12345674`. Not used for
     * employee documents, where `ES…` is simply invalid.
     */
    fun normalizeTaxId(value: String): String {
        val n = normalize(value)
        return if (n.startsWith("ES") && n.length == 11) n.substring(2) else n
    }

    fun isValidDniOrNie(normalized: String): Boolean {
        val number = when {
            DNI.matches(normalized) -> normalized.dropLast(1)
            NIE.matches(normalized) -> NIE_PREFIXES[normalized[0]] + normalized.substring(1).dropLast(1)
            else -> return false
        }
        return normalized.last() == DNI_LETTERS[number.toLong().mod(23)]
    }

    fun isValidCif(normalized: String): Boolean {
        if (!CIF.matches(normalized)) return false
        val digits = normalized.substring(1, 8).map { it.digitToInt() }
        // Positions 1, 3, 5, 7 (indices 0, 2, 4, 6) doubled, digits of the result added.
        val oddSum = digits.filterIndexed { i, _ -> i % 2 == 0 }.sumOf { (it * 2) / 10 + (it * 2) % 10 }
        val evenSum = digits.filterIndexed { i, _ -> i % 2 == 1 }.sum()
        val control = (10 - (oddSum + evenSum) % 10) % 10

        val entity = normalized[0]
        val last = normalized.last()
        val byDigit = last == control.digitToChar()
        val byLetter = last == CIF_LETTERS[control]
        return when (entity) {
            in LETTER_CONTROL_ENTITIES -> byLetter
            in DIGIT_CONTROL_ENTITIES -> byDigit
            else -> byDigit || byLetter
        }
    }

    /** DNI, NIE or CIF. */
    fun isValid(normalized: String): Boolean = isValidDniOrNie(normalized) || isValidCif(normalized)
}
