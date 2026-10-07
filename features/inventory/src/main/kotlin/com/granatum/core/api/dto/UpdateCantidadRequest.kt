package com.granatum.core.api.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class UpdateCantidadRequest(
    @field:Min(0)
    val cantidadDisponible: Int,

    @field:NotBlank
    val motivo: String
)
