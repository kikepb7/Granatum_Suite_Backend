package com.granatum.core.api.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * A valid Spanish DNI or NIE, checksum included.
 *
 * Validating the check letter rather than accepting any string matters because
 * this field legally identifies a person and ends up in a register handed to
 * the labour inspectorate. A typo caught at registration is a typo that never
 * reaches that document.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [DocumentoIdentidadValidator::class])
annotation class DocumentoIdentidad(
    val message: String = "El documento de identidad no es un DNI o NIE valido",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

class DocumentoIdentidadValidator : ConstraintValidator<DocumentoIdentidad, String> {

    override fun isValid(valor: String?, context: ConstraintValidatorContext?): Boolean {
        if (valor.isNullOrBlank()) return false
        return esValido(normalizar(valor))
    }

    companion object {
        private const val LETRAS_CONTROL = "TRWAGMYFPDXBNJZSQVHLCKE"

        /** NIE prefixes map onto a leading digit before the checksum is computed. */
        private val PREFIJOS_NIE = mapOf('X' to "0", 'Y' to "1", 'Z' to "2")

        private val DNI = Regex("^\\d{8}[A-Z]$")
        private val NIE = Regex("^[XYZ]\\d{7}[A-Z]$")

        /**
         * Upper-cased, with spaces and hyphens stripped.
         *
         * Normalising before the uniqueness check is what stops FR-029 being
         * bypassed by punctuation: without it `12345678z` and `12345678-Z`
         * would be two different people as far as the unique index is
         * concerned, which is the same person registered twice.
         */
        fun normalizar(valor: String): String =
            valor.uppercase().replace(" ", "").replace("-", "")

        fun esValido(normalizado: String): Boolean {
            val numero = when {
                DNI.matches(normalizado) -> normalizado.dropLast(1)
                NIE.matches(normalizado) ->
                    PREFIJOS_NIE[normalizado[0]] + normalizado.substring(1).dropLast(1)
                else -> return false
            }

            val esperada = LETRAS_CONTROL[numero.toLong().mod(23)]
            return normalizado.last() == esperada
        }
    }
}
