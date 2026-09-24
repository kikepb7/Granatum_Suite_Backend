package com.granatum.core.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateCategoriaRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val nombre: String,

    @field:Size(max = 500)
    val descripcion: String? = null
)
