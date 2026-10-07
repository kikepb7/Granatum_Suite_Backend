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

    private const val LETRAS_DNI = "TRWAGMYFPDXBNJZSQVHLCKE"

    /** NIE prefixes map onto a leading digit before the checksum is computed. */
    private val PREFIJOS_NIE = mapOf('X' to "0", 'Y' to "1", 'Z' to "2")

    private val DNI = Regex("^\\d{8}[A-Z]$")
    private val NIE = Regex("^[XYZ]\\d{7}[A-Z]$")
    private val CIF = Regex("^[ABCDEFGHJNPQRSUVW]\\d{7}[0-9A-J]$")

    private const val LETRAS_CIF = "JABCDEFGHI"
    /** Entities whose control is always a letter; [CONTROL_DIGITO] always a digit; the rest, either. */
    private const val CONTROL_LETRA = "NPQRSW"
    private const val CONTROL_DIGITO = "ABEH"

    /**
     * Upper-cased, spaces and hyphens stripped - and nothing else. It is what
     * timetracking's uniqueness of identity documents relies on, so it must
     * not change: `12345678z` and `12345678-Z` are the same person.
     */
    fun normalizar(valor: String): String = valor.uppercase().replace(" ", "").replace("-", "")

    /**
     * [normalizar] plus the `ES` country prefix of a VAT number, which invoices
     * often carry: `ESB12345674` is the company `B12345674`. Not used for
     * employee documents, where `ES…` is simply invalid.
     */
    fun normalizarFiscal(valor: String): String {
        val n = normalizar(valor)
        return if (n.startsWith("ES") && n.length == 11) n.substring(2) else n
    }

    fun esDniONieValido(normalizado: String): Boolean {
        val numero = when {
            DNI.matches(normalizado) -> normalizado.dropLast(1)
            NIE.matches(normalizado) -> PREFIJOS_NIE[normalizado[0]] + normalizado.substring(1).dropLast(1)
            else -> return false
        }
        return normalizado.last() == LETRAS_DNI[numero.toLong().mod(23)]
    }

    fun esCifValido(normalizado: String): Boolean {
        if (!CIF.matches(normalizado)) return false
        val digitos = normalizado.substring(1, 8).map { it.digitToInt() }
        // Positions 1, 3, 5, 7 (indices 0, 2, 4, 6) doubled, digits of the result added.
        val impares = digitos.filterIndexed { i, _ -> i % 2 == 0 }.sumOf { (it * 2) / 10 + (it * 2) % 10 }
        val pares = digitos.filterIndexed { i, _ -> i % 2 == 1 }.sum()
        val control = (10 - (impares + pares) % 10) % 10

        val entidad = normalizado[0]
        val final = normalizado.last()
        val porDigito = final == control.digitToChar()
        val porLetra = final == LETRAS_CIF[control]
        return when (entidad) {
            in CONTROL_LETRA -> porLetra
            in CONTROL_DIGITO -> porDigito
            else -> porDigito || porLetra
        }
    }

    /** DNI, NIE or CIF. */
    fun esValido(normalizado: String): Boolean = esDniONieValido(normalizado) || esCifValido(normalizado)
}
