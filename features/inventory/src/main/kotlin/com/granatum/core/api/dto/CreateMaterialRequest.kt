package com.granatum.core.api.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreateMaterialRequest(
    @field:NotNull
    val categoriaId: UUID?,

    @field:NotBlank
    @field:Size(max = 500)
    val descripcion: String?,

    @field:Valid
    @field:NotNull
    val dimensiones: DimensionesRequest?,

    @field:NotNull
    @field:Min(0)
    val cantidad: Int?,

    @field:Valid
    @field:NotNull
    val localizacion: LocalizacionRequest?
)
