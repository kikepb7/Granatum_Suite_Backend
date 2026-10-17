package com.granatum.core.api.dto

import com.granatum.core.domain.type.ExportScope
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.entities.RegisterExportEntity
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
data class RegisterExportDto(
    val id: UUID,
    val requesterId: UUID,
    val requesterRole: Role,
    val scope: ExportScope,
    val employeeId: UUID?,
    val from: LocalDate,
    val to: LocalDate,
    val generatedAt: Instant,
    val completed: Boolean,
    val rowCount: Int,
    val fingerprint: String?
) {
    override fun toString(): String = "RegisterExportDto(id=$id, scope=$scope, completed=$completed)"
}

/** The answer to "is this file one we handed over?" (FR-028). */
data class ExportVerificationDto(
    val fingerprint: String,
    val matches: Boolean,
    val exports: List<RegisterExportDto>
) {
    override fun toString(): String = "ExportVerificationDto(matches=$matches, exports=${exports.size})"
}

fun RegisterExportEntity.toDto() = RegisterExportDto(
    id = id,
    requesterId = requesterId,
    requesterRole = requesterRole,
    scope = scope,
    employeeId = employeeId,
    from = from,
    to = to,
    generatedAt = generatedAt,
    completed = completed,
    rowCount = rowCount,
    fingerprint = fingerprint
)
