package com.granatum.core.domain.model

import com.granatum.core.domain.type.UnidadMedida
import java.math.BigDecimal

data class TamanoModel(
    val alto: BigDecimal,
    val ancho: BigDecimal,
    val diametro: BigDecimal?,
    val unidadMedida: UnidadMedida
)
