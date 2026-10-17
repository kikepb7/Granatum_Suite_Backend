package com.granatum.core.api.dto

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

/** Centimetres, up to two decimals (FR-005). */
data class DimensionesRequest(
    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @field:Digits(integer = 8, fraction = 2)
    val alto: BigDecimal?,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @field:Digits(integer = 8, fraction = 2)
    val ancho: BigDecimal?
)
