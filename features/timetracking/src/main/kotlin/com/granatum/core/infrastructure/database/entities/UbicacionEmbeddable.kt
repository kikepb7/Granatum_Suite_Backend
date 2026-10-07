package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.math.BigDecimal

/**
 * Optional location of a clock-in or clock-out.
 *
 * NUMERIC(9,6) rather than a floating point type: ~0.1 m of resolution, which
 * is far more than needed, and no binary rounding error.
 *
 * This is personal data and the most intrusive datum in the register. It never
 * reaches logs, and it is never shown to the REPRESENTANTE role.
 */
@Embeddable
class UbicacionEmbeddable(
    @Column(precision = 9, scale = 6)
    var latitud: BigDecimal,

    @Column(precision = 9, scale = 6)
    var longitud: BigDecimal,

    var precisionMetros: Int? = null
)
