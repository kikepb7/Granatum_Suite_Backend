package com.granatum.core.api.dto

import com.granatum.core.domain.type.AlcanceRegistro
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.entities.ExportacionEntity
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One row of the export log. Identifiers, range, count and fingerprint, and
 * nothing that was exported (FR-026).
 *
 * `toString()` is overridden even though every field is an id or a number: in
 * feature 002 Spring MVC logged DTOs at DEBUG through their `toString()`, and
 * keeping every DTO on the same pattern means nobody has to re-check which
 * ones are "safe" when a field is added.
 */
data class ExportacionDto(
    val id: UUID,
    val solicitanteId: UUID,
    val rolSolicitante: Role,
    val alcance: AlcanceRegistro,
    val empleadoId: UUID?,
    val desde: LocalDate,
    val hasta: LocalDate,
    val generadaEn: Instant,
    val completada: Boolean,
    val filas: Int,
    val huella: String?
) {
    override fun toString(): String = "ExportacionDto(id=$id, alcance=$alcance, completada=$completada)"
}

/** The answer to "is this file one we handed over?" (FR-028). */
data class VerificacionDto(
    val huella: String,
    val coincide: Boolean,
    val exportaciones: List<ExportacionDto>
) {
    override fun toString(): String = "VerificacionDto(coincide=$coincide, exportaciones=${exportaciones.size})"
}

fun ExportacionEntity.toDto() = ExportacionDto(
    id = id,
    solicitanteId = solicitanteId,
    rolSolicitante = rolSolicitante,
    alcance = alcance,
    empleadoId = empleadoId,
    desde = desde,
    hasta = hasta,
    generadaEn = generadaEn,
    completada = completada,
    filas = filas,
    huella = huella
)
