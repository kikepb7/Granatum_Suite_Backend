package com.granatum.core.api.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * Full update of a material's attributes.
 * `cantidad` is intentionally excluded - it can only change through
 * `PATCH /api/materiales/{id}/cantidad`, which enforces a mandatory `motivo`
 * and records the change in the historial (research.md D-008). The image has
 * its own routes.
 */
data class UpdateMaterialRequest(
    @field:NotNull
    val categoriaId: UUID?,

    @field:NotBlank
    @field:Size(max = 500)
    val descripcion: String?,

    @field:Valid
    @field:NotNull
    val dimensiones: DimensionesRequest?,

    @field:Valid
    @field:NotNull
    val localizacion: LocalizacionRequest?
)
