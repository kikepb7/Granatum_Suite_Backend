package com.granatum.core.api.dto

import com.granatum.core.api.validation.DocumentoIdentidad
import com.granatum.core.domain.type.TipoContrato
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.util.UUID

data class CrearEmpleadoRequest(
    @field:NotBlank @field:Size(min = 1, max = 150) val nombre: String,
    @field:NotBlank @field:DocumentoIdentidad val documentoIdentidad: String,
    @field:NotBlank @field:Size(min = 1, max = 100) val puesto: String,
    @field:NotNull val tipoContrato: TipoContrato,
    /** A civil date: an engagement is an administrative fact of a day, not an instant. */
    @field:NotNull val fechaAlta: LocalDate
)

/**
 * Carries neither `documentoIdentidad` nor `activo`, by design.
 *
 * Correcting someone's identity document is an exceptional case that deserves
 * its own audited operation, not a general PUT - and a request that includes it
 * is rejected rather than ignored, so a client never believes it changed
 * something it did not.
 *
 * `activo` has its own endpoint so that deactivating someone is an explicit act
 * and not the side effect of editing their job title.
 */
data class ActualizarEmpleadoRequest(
    @field:NotBlank @field:Size(min = 1, max = 150) val nombre: String,
    @field:NotBlank @field:Size(min = 1, max = 100) val puesto: String,
    @field:NotNull val tipoContrato: TipoContrato,
    @field:NotNull val fechaAlta: LocalDate,
    /**
     * Present only so an attempt to change it can be refused explicitly. Any
     * non-null value here produces a validation error.
     */
    val documentoIdentidad: String? = null
)

data class CambiarActivoRequest(
    @field:NotNull val activo: Boolean
)

data class EmpleadoDto(
    val id: UUID,
    val nombre: String,
    val documentoIdentidad: String,
    val puesto: String,
    val tipoContrato: TipoContrato,
    val fechaAlta: LocalDate,
    val activo: Boolean
)
