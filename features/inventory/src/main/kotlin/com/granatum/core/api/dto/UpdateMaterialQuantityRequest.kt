package com.granatum.core.api.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class UpdateMaterialQuantityRequest(
    @field:NotNull
    @field:Min(0)
    val quantity: Int?,

    @field:NotBlank
    val reason: String?
)
