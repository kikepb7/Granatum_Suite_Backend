package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.math.BigDecimal

@Embeddable
class MaterialDimensionsEmbeddable(
    @Column(nullable = false, precision = 10, scale = 2)
    var height: BigDecimal,

    @Column(nullable = false, precision = 10, scale = 2)
    var width: BigDecimal
)
