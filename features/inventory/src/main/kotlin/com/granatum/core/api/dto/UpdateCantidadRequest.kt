package com.granatum.core.api.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class UpdateCantidadRequest(
    @field:NotNull
    @field:Min(0)
    val cantidad: Int?,

    @field:NotBlank
    val motivo: String?
)
