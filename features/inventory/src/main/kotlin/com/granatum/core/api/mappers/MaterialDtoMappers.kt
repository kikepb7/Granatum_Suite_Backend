package com.granatum.core.api.mappers

import com.granatum.core.api.dto.DimensionesDto
import com.granatum.core.api.dto.DimensionesRequest
import com.granatum.core.api.dto.LocalizacionDto
import com.granatum.core.api.dto.LocalizacionRequest
import com.granatum.core.api.dto.MaterialDto
import com.granatum.core.domain.model.DimensionesModel
import com.granatum.core.domain.model.Localizacion
import com.granatum.core.domain.model.MaterialModel
import com.granatum.core.domain.type.Almacen

fun MaterialModel.toDto(): MaterialDto = MaterialDto(
    id = id,
    categoria = categoria.toDto(),
    descripcion = descripcion,
    dimensiones = DimensionesDto(alto = dimensiones.alto, ancho = dimensiones.ancho),
    cantidad = cantidad,
    localizacion = LocalizacionDto(almacen = localizacion.almacen, seccion = localizacion.seccion),
    imagenUrl = imagenUrl,
    fechaAlta = fechaAlta,
    fechaUltimaModificacion = fechaUltimaModificacion
)

// The `!!` below are safe: these requests reach the mappers only after @Valid
// has checked @NotNull on every one of these fields.

fun DimensionesRequest.toModel(): DimensionesModel = DimensionesModel(alto = alto!!, ancho = ancho!!)

fun LocalizacionRequest.toModel(): Localizacion = Localizacion(almacen = Almacen.valueOf(almacen!!), seccion = seccion!!)
