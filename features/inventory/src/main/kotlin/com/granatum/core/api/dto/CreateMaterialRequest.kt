package com.granatum.core.api.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateMaterialRequest(
    @field:NotNull
    val categoryId: UUID?,

    @field:NotBlank
    @field:Size(max = 500)
    val description: String?,

    @field:Valid
    @field:NotNull
    val dimensions: MaterialDimensionsRequest?,

    @field:NotNull
    @field:Min(0)
    val quantity: Int?,

    @field:Valid
    @field:NotNull
    val location: MaterialLocationRequest?
)
