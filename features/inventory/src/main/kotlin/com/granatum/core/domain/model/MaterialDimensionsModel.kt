package com.granatum.core.domain.model

import java.math.BigDecimal

/** Height and width, always in centimetres (FR-005). */
data class MaterialDimensionsModel(
    val height: BigDecimal,
    val width: BigDecimal
)
