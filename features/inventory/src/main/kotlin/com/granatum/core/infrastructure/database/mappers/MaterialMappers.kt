package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.DimensionesModel
import com.granatum.core.domain.model.Localizacion
import com.granatum.core.domain.model.MaterialModel
import com.granatum.core.infrastructure.database.entities.DimensionesEmbeddable
import com.granatum.core.infrastructure.database.entities.LocalizacionEmbeddable
import com.granatum.core.infrastructure.database.entities.MaterialEntity

fun DimensionesEmbeddable.toModel(): DimensionesModel = DimensionesModel(alto = alto, ancho = ancho)

fun LocalizacionEmbeddable.toModel(): Localizacion = Localizacion(almacen = almacen, seccion = seccion)

fun MaterialEntity.toModel(): MaterialModel = MaterialModel(
    id = id,
    categoria = categoria.toModel(),
    descripcion = descripcion,
    dimensiones = dimensiones.toModel(),
    cantidad = cantidad,
    localizacion = localizacion.toModel(),
    imagenRuta = imagenRuta,
    fechaAlta = fechaAlta,
    fechaUltimaModificacion = fechaUltimaModificacion
)
