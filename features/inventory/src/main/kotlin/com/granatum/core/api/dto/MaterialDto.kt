package com.granatum.core.api.dto

import com.granatum.core.domain.type.Almacen
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class MaterialDto(
    val id: UUID,
    val categoria: CategoriaDto,
    val descripcion: String,
    val dimensiones: DimensionesDto,
    val cantidad: Int,
    val localizacion: LocalizacionDto,
    /** Temporary (FR-018). Null without an image, or when it could not be signed. */
    val imagenUrl: String?,
    val fechaAlta: Instant,
    val fechaUltimaModificacion: Instant
)

/** Centimetres. */
data class DimensionesDto(
    val alto: BigDecimal,
    val ancho: BigDecimal
)

data class LocalizacionDto(
    val almacen: Almacen,
    val seccion: Int
)
