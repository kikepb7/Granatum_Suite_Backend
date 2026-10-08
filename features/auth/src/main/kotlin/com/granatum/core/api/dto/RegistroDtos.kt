package com.granatum.core.api.dto

import com.granatum.core.domain.type.Role
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.util.UUID

/*
 * The owner's sign-up (feature 005 US1, feature 009) and onboarding by an ADMIN
 * (feature 009). Every request and response overrides toString: they carry a
 * password, a code, a name, an email or an identity document, and a data class
 * would print them in any log line or exception that mentions the object
 * (principle VI).
 */

data class RegistroRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String = "",

    /** Capped at 128 like LoginRequest: what stops a huge body becoming Argon2 work. */
    @field:NotNull
    @field:Size(max = 128)
    val password: String = "",

    @field:NotBlank
    @field:Size(max = 150)
    val nombre: String = "",

    @field:NotBlank
    @field:Size(max = 20)
    val documentoIdentidad: String = "",

    /**
     * Required: only the business owner signs up, once, with the code set at
     * deployment (feature 009). Everyone else is onboarded by an ADMIN.
     */
    @field:NotBlank
    @field:Size(max = 200)
    val codigoArranque: String = ""
) {
    override fun toString(): String = "RegistroRequest(arranque=***)"
}

data class RegistroResponse(
    /** Always `ACTIVA`: the owner's account exists and can sign in. */
    val estado: String,
    val mensaje: String
)

/**
 * Onboarding a person in one step (feature 009): their staff record and their
 * account. `puesto`, `tipoContrato` and `fechaAlta` are the staff record's; if a
 * record with this document already exists, they are ignored and the account
 * is linked to it.
 */
data class AltaPersonaRequest(
    @field:NotBlank
    @field:Size(max = 150)
    val nombre: String = "",

    @field:NotBlank
    @field:Size(max = 20)
    val documentoIdentidad: String = "",

    @field:NotBlank
    @field:Size(max = 100)
    val puesto: String = "",

    @field:NotBlank
    @field:Pattern(regexp = "JORNADA_COMPLETA|PARCIAL|POR_HORAS")
    val tipoContrato: String = "",

    @field:NotNull
    val fechaAlta: LocalDate? = null,

    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String = "",

    @field:NotNull
    val rol: Role? = null
) {
    override fun toString(): String = "AltaPersonaRequest(tipoContrato=$tipoContrato, rol=$rol)"
}

data class AltaPersonaResponse(
    val empleadoId: UUID,
    val cuentaId: UUID,
    val email: String,
    val rol: Role,
    /** False when the account was linked to an existing staff record with this document. */
    val fichaCreada: Boolean,
    /**
     * The temporary password, **only here and only once**: it is not stored in
     * clear and cannot be read back. The ADMIN hands it to the person, who must
     * change it on first sign-in. If it is lost, reset it.
     */
    val passwordTemporal: String
) {
    override fun toString(): String =
        "AltaPersonaResponse(empleadoId=$empleadoId, cuentaId=$cuentaId, rol=$rol, fichaCreada=$fichaCreada, " +
            "email=***, passwordTemporal=***)"
}
