package com.granatum.core.domain.model

import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EstadoMaterial
import java.math.BigDecimal
import java.time.Instant

data class MaterialModel(
    val id: EntityId,
    val nombre: String,
    val categoria: CategoriaModel,
    val cantidadDisponible: Int,
    val cantidadTotal: Int,
    val tamano: TamanoModel,
    val color: String,
    val materialFisico: String,
    val estado: EstadoMaterial,
    val ubicacion: String,
    val precioUnitario: BigDecimal,
    val proveedor: String,
    val fotos: List<String>,
    val fechaAlta: Instant,
    val fechaUltimaModificacion: Instant
)
