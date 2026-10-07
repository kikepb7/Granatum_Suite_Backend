package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.AlcanceRegistro
import com.granatum.core.domain.type.Role
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One export or monthly download, complete or cut short (FR-025, D-003).
 * Append-only: every field is a `val`, and the repository has no delete.
 *
 * Holds **no exported data** (FR-026): no hours, no names, no documents, no
 * file. Identifiers, the range, the row count and the fingerprint are enough to
 * say who took what and to recognise the file later (FR-028).
 */
@Entity
@Table(name = "exportaciones")
class ExportacionEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    /** The token subject. No foreign key: nothing may cascade into an immutable table. */
    @Column(name = "solicitante_id", nullable = false)
    val solicitanteId: UUID,

    /** Says which version of the file was handed over: with or without the document (D-012). */
    @Enumerated(EnumType.STRING)
    @Column(name = "rol_solicitante", nullable = false, length = 20)
    val rolSolicitante: Role,

    @Enumerated(EnumType.STRING)
    @Column(name = "alcance", nullable = false, length = 10)
    val alcance: AlcanceRegistro,

    /** Null if and only if the whole staff was exported. */
    @Column(name = "empleado_id")
    val empleadoId: UUID?,

    /** The **effective** range, already trimmed by the retention period (D-009). */
    @Column(name = "desde", nullable = false)
    val desde: LocalDate,

    @Column(name = "hasta", nullable = false)
    val hasta: LocalDate,

    @Column(name = "generada_en", nullable = false)
    val generadaEn: Instant,

    /** False when the client disconnected or the time limit ran out (D-003). */
    @Column(name = "completada", nullable = false)
    val completada: Boolean,

    /** Data rows written, without the header or the monthly block. */
    @Column(name = "filas", nullable = false)
    val filas: Int,

    /** SHA-256 in hex of the exact bytes sent; null if and only if not completed (D-006). */
    @Column(name = "huella", length = 64)
    val huella: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExportacionEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String =
        "ExportacionEntity(id=$id, alcance=$alcance, completada=$completada)"
}
