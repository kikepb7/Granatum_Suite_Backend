package com.granatum.core.domain.type

/**
 * What kind of field changed on a [com.granatum.core.domain.model.MaterialModel],
 * as recorded by an immutable [com.granatum.core.domain.model.HistorialMaterialModel] entry.
 */
enum class TipoCambioHistorial {
    CANTIDAD,
    ESTADO,
    UBICACION,
    PRECIO_UNITARIO,
    PROVEEDOR,
    OTRO
}
