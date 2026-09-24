package com.granatum.core.api.mappers

import com.granatum.core.api.dto.MaterialDto
import com.granatum.core.api.dto.TamanoDto
import com.granatum.core.domain.model.MaterialModel
import com.granatum.core.domain.model.TamanoModel

fun TamanoModel.toDto(): TamanoDto = TamanoDto(
    alto = alto,
    ancho = ancho,
    diametro = diametro,
    unidadMedida = unidadMedida
)

fun MaterialModel.toDto(): MaterialDto = MaterialDto(
    id = id,
    nombre = nombre,
    categoria = categoria.toDto(),
    cantidadDisponible = cantidadDisponible,
    cantidadTotal = cantidadTotal,
    tamano = tamano.toDto(),
    color = color,
    materialFisico = materialFisico,
    estado = estado,
    ubicacion = ubicacion,
    precioUnitario = precioUnitario,
    proveedor = proveedor,
    fotos = fotos,
    fechaAlta = fechaAlta,
    fechaUltimaModificacion = fechaUltimaModificacion
)
