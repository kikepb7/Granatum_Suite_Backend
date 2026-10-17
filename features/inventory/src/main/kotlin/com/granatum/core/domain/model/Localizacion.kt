package com.granatum.core.domain.model

import com.granatum.core.domain.type.Almacen

/** Where a material is: one warehouse and one of its sections (FR-008). */
data class Localizacion(
    val almacen: Almacen,
    val seccion: Int
) {
    init {
        require(seccion in SECCIONES) { "La seccion debe estar entre ${SECCIONES.first} y ${SECCIONES.last}" }
    }

    companion object {
        /** Every warehouse has the same five sections. */
        val SECCIONES = 1..5
    }
}
