package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.EventoMaterialModel
import com.granatum.core.domain.model.EventoModel
import com.granatum.core.infrastructure.database.entities.EventoEntity
import com.granatum.core.infrastructure.database.entities.EventoMaterialEntity

fun EventoEntity.toModel(materiales: List<EventoMaterialEntity>) = EventoModel(
    id = id,
    nombre = nombre,
    descripcion = descripcion,
    fecha = fecha,
    estado = estado,
    materiales = materiales.map { it.toModel() }.sortedWith(compareBy({ it.categoria.lowercase() }, { it.descripcion.lowercase() })),
    creadoPor = creadoPor,
    finalizadoEn = finalizadoEn,
    fechaAlta = createdAt,
    fechaUltimaModificacion = updatedAt
)

fun EventoMaterialEntity.toModel() = EventoMaterialModel(
    materialId = materialId,
    categoria = categoria,
    descripcion = descripcion,
    cantidad = cantidad
)
