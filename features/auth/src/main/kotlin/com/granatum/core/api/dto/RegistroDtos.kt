package com.granatum.core.api.dto

import com.granatum.core.domain.type.Role
import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/*
 * Feature 005, specs/005-staff-registration/contracts/README.md. Every request
 * and response overrides toString: they carry a password, a code, a name, an
 * email or an identity document, and a data class would print them in any log
 * line or exception that mentions the object (principle VI).
 */

data class RegistroRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String = "",

    /**
     * Capped at 128 like LoginRequest: the policy's maximum, and what stops a
     * multi-megabyte body from becoming Argon2 work on a public route. The
     * policy itself (422 PASSWORD_DEBIL with the unmet requirements) runs in
     * the service, so it answers with the same detail as a password change.
     */
    @field:NotNull
    @field:Size(max = 128)
    val password: String = "",

    @field:NotBlank
    @field:Size(max = 150)
    val nombre: String = "",

    @field:NotBlank
    @field:Size(max = 20)
    val documentoIdentidad: String = "",

    /** Only for the first ADMIN of an installation (research.md D-002). */
    @field:Size(max = 200)
    val codigoArranque: String? = null
) {
    override fun toString(): String = "RegistroRequest(arranque=${codigoArranque != null})"
}

data class RegistroResponse(
    /** `PENDIENTE_APROBACION` or `ACTIVA`. */
    val estado: String,
    val codigoVerificacion: String?,
    val mensaje: String
) {
    override fun toString(): String = "RegistroResponse(estado=$estado)"
}

data class SolicitudRegistroResponse(
    val id: UUID,
    val email: String,
    val nombre: String,
    val documentoIdentidad: String,
    val creadaEn: Instant,
    /** `null` when no staff record has this document: approving must then create it. */
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val empleadoExistenteId: UUID?
) {
    override fun toString(): String = "SolicitudRegistroResponse(id=$id, creadaEn=$creadaEn)"
}

data class AprobarRegistroRequest(
    @field:NotBlank
    @field:Size(max = 20)
    val codigoVerificacion: String = "",

    @field:NotNull
    val rol: Role? = null,

    /** Only used when no staff record has the request's document. */
    @field:Size(min = 1, max = 100)
    val puesto: String? = null,

    @field:Pattern(regexp = "JORNADA_COMPLETA|PARCIAL|POR_HORAS")
    val tipoContrato: String? = null,

    val fechaAlta: LocalDate? = null
) {
    override fun toString(): String =
        "AprobarRegistroRequest(codigoVerificacion=***, rol=$rol, tipoContrato=$tipoContrato)"
}

data class RegistroAprobadoResponse(
    val solicitudId: UUID,
    val cuentaId: UUID,
    val empleadoId: UUID,
    val rol: Role,
    val fichaCreada: Boolean
)
