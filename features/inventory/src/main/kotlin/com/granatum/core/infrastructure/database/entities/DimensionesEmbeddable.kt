package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.math.BigDecimal

@Embeddable
class DimensionesEmbeddable(
    @Column(nullable = false, precision = 10, scale = 2)
    var alto: BigDecimal,

    @Column(nullable = false, precision = 10, scale = 2)
    var ancho: BigDecimal
)
