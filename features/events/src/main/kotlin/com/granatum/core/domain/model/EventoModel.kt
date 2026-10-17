package com.granatum.core.domain.model

import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EstadoEvento
import java.time.Instant
import java.time.LocalDate

data class EventoModel(
    val id: EntityId,
    val nombre: String,
    val descripcion: String?,
    val fecha: LocalDate,
    val estado: EstadoEvento,
    val materiales: List<EventoMaterialModel>,
    val creadoPor: EntityId,
    val finalizadoEn: Instant?,
    val fechaAlta: Instant,
    val fechaUltimaModificacion: Instant
)

/**
 * A material an event takes. [categoria] and [descripcion] are those it had when assigned, so a
 * finished event still says what it took if the material is later deleted; [almacen], [seccion]
 * and [imagenUrl] are the inventory's current ones, `null` when it no longer exists.
 */
data class EventoMaterialModel(
    val materialId: EntityId,
    val categoria: String,
    val descripcion: String,
    val cantidad: Int,
    val almacen: String? = null,
    val seccion: Int? = null,
    val imagenUrl: String? = null
)

/** What a person writes when creating or editing an event. */
data class DatosEvento(
    val nombre: String,
    val descripcion: String?,
    val fecha: LocalDate
)
