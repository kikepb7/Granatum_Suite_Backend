package com.granatum.core.api.dto

import com.granatum.core.domain.type.EstadoMaterial
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class MaterialDto(
    val id: UUID,
    val nombre: String,
    val categoria: CategoriaDto,
    val cantidadDisponible: Int,
    val cantidadTotal: Int,
    val tamano: TamanoDto,
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
