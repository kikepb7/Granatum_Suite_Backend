package com.granatum.core.api.dto

import com.granatum.core.domain.type.EstadoEvento
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Names carry "Evento"/"Disponible" on purpose: every module shares the com.granatum.core.api.dto
// package, and inventory already has a LocalizacionDto (SinColisionDeClasesIT).

/** Creating or editing an event: the whole of what a person writes. */
data class EventoRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val nombre: String?,

    @field:Size(max = 500)
    val descripcion: String?,

    @field:NotNull
    val fecha: LocalDate?
)

/** How many units of a material the event takes; to take none, remove it. */
data class CantidadMaterialEventoRequest(
    @field:NotNull
    @field:Min(1)
    val cantidad: Int?
)

data class EventoDto(
    val id: UUID,
    val nombre: String,
    val descripcion: String?,
    val fecha: LocalDate,
    val estado: EstadoEvento,
    val materiales: List<EventoMaterialDto>,
    val creadoPor: UUID,
    val finalizadoEn: Instant?,
    val fechaAlta: Instant,
    val fechaUltimaModificacion: Instant
)

/**
 * `categoria` and `descripcion` as they were when assigned; `localizacion` and `imagenUrl` as the
 * inventory has them now, `null` when the material no longer exists.
 */
data class EventoMaterialDto(
    val materialId: UUID,
    val categoria: String,
    val descripcion: String,
    val cantidad: Int,
    val localizacion: LocalizacionEventoDto?,
    val imagenUrl: String?
)

/** A material that can be taken to an event, with its free units (FR-012). */
data class MaterialDisponibleDto(
    val id: UUID,
    val categoria: String,
    val descripcion: String,
    val cantidad: Int,
    val localizacion: LocalizacionEventoDto,
    val imagenUrl: String?
)

data class LocalizacionEventoDto(
    val almacen: String,
    val seccion: Int
)
