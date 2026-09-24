package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.MaterialModel
import com.granatum.core.domain.model.TamanoModel
import com.granatum.core.infrastructure.database.entities.MaterialEntity
import com.granatum.core.infrastructure.database.entities.TamanoEmbeddable

fun TamanoEmbeddable.toModel(): TamanoModel = TamanoModel(
    alto = alto,
    ancho = ancho,
    diametro = diametro,
    unidadMedida = unidadMedida
)

fun MaterialEntity.toModel(): MaterialModel = MaterialModel(
    id = id,
    nombre = nombre,
    categoria = categoria.toModel(),
    cantidadDisponible = cantidadDisponible,
    cantidadTotal = cantidadTotal,
    tamano = tamano.toModel(),
    color = color,
    materialFisico = materialFisico,
    estado = estado,
    ubicacion = ubicacion,
    precioUnitario = precioUnitario,
    proveedor = proveedor,
    fotos = fotos.toList(),
    fechaAlta = fechaAlta,
    fechaUltimaModificacion = fechaUltimaModificacion
)
