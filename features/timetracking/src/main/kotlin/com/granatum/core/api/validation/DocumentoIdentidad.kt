package com.granatum.core.api.validation

import com.granatum.core.validation.NifValidator
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
        /**
         * Upper-cased, with spaces and hyphens stripped.
         *
         * Normalising before the uniqueness check is what stops FR-029 being
         * bypassed by punctuation: without it `12345678z` and `12345678-Z`
         * would be two different people as far as the unique index is
         * concerned, which is the same person registered twice.
         *
         * The rule itself lives in common's [NifValidator] since feature 004,
         * which validates invoice tax ids with it.
         */
        fun normalizar(valor: String): String = NifValidator.normalizar(valor)

        fun esValido(normalizado: String): Boolean = NifValidator.esDniONieValido(normalizado)
    }
}
