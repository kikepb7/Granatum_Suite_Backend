package com.granatum.core.domain.contract

import com.granatum.core.domain.type.EntityId

/**
 * Whether a material is out at an event that has not finished (feature 011). `events` implements
 * it; `inventory` asks before deleting a material, because deleting one whose units are at a
 * wedding would lose track of them (specs/011-events FR-013). The reverse direction of
 * [StockMateriales], and a separate contract so each feature depends only on what it uses.
 */
interface UsoMateriales {
    fun enUso(materialId: EntityId): Boolean
}
