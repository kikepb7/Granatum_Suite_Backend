package com.granatum.core.domain.model

import java.math.BigDecimal

/** Height and width, always in centimetres (FR-005). */
data class DimensionesModel(
    val alto: BigDecimal,
    val ancho: BigDecimal
)
