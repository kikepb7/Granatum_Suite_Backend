package com.granatum.core.api.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * Full update of a material's attributes.
 * `quantity` is intentionally excluded - it can only change through
 * `PATCH /api/materials/{id}/quantity`, which enforces a mandatory `reason`
 * and records the change in the history (research.md D-008). The image has
 * its own routes.
 */
data class UpdateMaterialRequest(
    @field:NotNull
    val categoryId: UUID?,

    @field:NotBlank
    @field:Size(max = 500)
    val description: String?,

    @field:Valid
    @field:NotNull
    val dimensions: MaterialDimensionsRequest?,

    @field:Valid
    @field:NotNull
    val location: MaterialLocationRequest?
)
