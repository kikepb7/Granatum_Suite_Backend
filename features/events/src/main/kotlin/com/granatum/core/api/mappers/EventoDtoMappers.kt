package com.granatum.core.api.mappers

import com.granatum.core.api.dto.EventoDto
import com.granatum.core.api.dto.EventoMaterialDto
import com.granatum.core.api.dto.LocalizacionEventoDto
import com.granatum.core.api.dto.MaterialDisponibleDto
import com.granatum.core.domain.contract.MaterialStock
import com.granatum.core.domain.model.EventoMaterialModel
import com.granatum.core.domain.model.EventoModel

fun EventoModel.toDto() = EventoDto(
    id = id,
    nombre = nombre,
    descripcion = descripcion,
    fecha = fecha,
    estado = estado,
    materiales = materiales.map { it.toDto() },
    creadoPor = creadoPor,
    finalizadoEn = finalizadoEn,
    fechaAlta = fechaAlta,
    fechaUltimaModificacion = fechaUltimaModificacion
)

fun EventoMaterialModel.toDto() = EventoMaterialDto(
    materialId = materialId,
    categoria = categoria,
    descripcion = descripcion,
    cantidad = cantidad,
    localizacion = if (almacen != null && seccion != null) LocalizacionEventoDto(almacen, seccion) else null,
    imagenUrl = imagenUrl
)

fun MaterialStock.toDisponibleDto() = MaterialDisponibleDto(
    id = id,
    categoria = categoria,
    descripcion = descripcion,
    cantidad = cantidad,
    localizacion = LocalizacionEventoDto(almacen, seccion),
    imagenUrl = imagenUrl
)
