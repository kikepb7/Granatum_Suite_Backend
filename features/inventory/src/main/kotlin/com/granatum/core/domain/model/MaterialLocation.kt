package com.granatum.core.domain.model

import com.granatum.core.domain.type.Warehouse

/** Where a material is: one warehouse and one of its sections (FR-008). */
data class MaterialLocation(
    val warehouse: Warehouse,
    val section: Int
) {
    init {
        require(section in SECTIONS) { "La seccion debe estar entre ${SECTIONS.first} y ${SECTIONS.last}" }
    }

    companion object {
        /** Every warehouse has the same five sections. */
        val SECTIONS = 1..5
    }
}
