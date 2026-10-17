package com.granatum.core.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateCategoryRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,

    @field:Size(max = 500)
    val description: String? = null
)
