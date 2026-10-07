package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.UnidadMedida
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import java.math.BigDecimal

@Embeddable
class TamanoEmbeddable(
    @Column(nullable = false, precision = 10, scale = 2)
    var alto: BigDecimal,

    @Column(nullable = false, precision = 10, scale = 2)
    var ancho: BigDecimal,

    @Column(precision = 10, scale = 2)
    var diametro: BigDecimal? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "unidad_medida", nullable = false, length = 20)
    var unidadMedida: UnidadMedida
)
