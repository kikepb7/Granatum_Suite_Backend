package com.granatum.core.domain.type

/**
 * What kind of field changed on a [com.granatum.core.domain.model.MaterialModel],
 * as recorded by an immutable [com.granatum.core.domain.model.MaterialHistoryModel] entry.
 */
enum class MaterialChangeType {
    QUANTITY,
    STATUS,
    LOCATION,
    UNIT_PRICE,
    SUPPLIER,
    OTHER
}
