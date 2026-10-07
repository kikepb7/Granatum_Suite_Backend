package com.granatum.core.api.dto

import com.granatum.core.domain.type.UnidadMedida
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

data class TamanoRequest(
    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    val alto: BigDecimal,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    val ancho: BigDecimal,

    @field:DecimalMin(value = "0.0", inclusive = false)
    val diametro: BigDecimal? = null,

    @field:NotNull
    val unidadMedida: UnidadMedida
)
