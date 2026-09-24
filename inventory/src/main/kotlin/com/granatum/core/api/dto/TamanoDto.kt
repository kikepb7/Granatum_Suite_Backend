package com.granatum.core.api.dto

import com.granatum.core.domain.type.UnidadMedida
import java.math.BigDecimal

data class TamanoDto(
    val alto: BigDecimal,
    val ancho: BigDecimal,
    val diametro: BigDecimal?,
    val unidadMedida: UnidadMedida
)
