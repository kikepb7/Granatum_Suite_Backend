package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.ExportDestination
import com.granatum.core.domain.type.ExportScope
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
@Table(name = "exports")
class RegisterExportEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    /** The token subject. No foreign key: nothing may cascade into an immutable table. */
    @Column(name = "requester_id", nullable = false)
    val requesterId: UUID,

    /** Says which version of the file was handed over: with or without the document (D-012). */
    @Enumerated(EnumType.STRING)
    @Column(name = "requester_role", nullable = false, length = 20)
    val requesterRole: Role,

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 10)
    val scope: ExportScope,

    /** Null if and only if the whole staff was exported. */
    @Column(name = "employee_id")
    val employeeId: UUID?,

    /** The **effective** range, already trimmed by the retention period (D-009). */
    @Column(name = "from_date", nullable = false)
    val from: LocalDate,

    @Column(name = "to_date", nullable = false)
    val to: LocalDate,

    @Column(name = "generated_at", nullable = false)
    val generatedAt: Instant,

    /** False when the client disconnected or the time limit ran out (D-003). */
    @Column(name = "completed", nullable = false)
    val completed: Boolean,

    /** Data rows written, without the header or the monthly block. */
    @Column(name = "row_count", nullable = false)
    val rowCount: Int,

    /** SHA-256 in hex of the exact bytes sent; null if and only if not completed (D-006). */
    @Column(name = "fingerprint", length = 64)
    val fingerprint: String?,

    /** A CSV file, or the month's tab of the Google spreadsheet (feature 012, D-006). */
    @Enumerated(EnumType.STRING)
    @Column(name = "destination", nullable = false, length = 20)
    val destination: ExportDestination = ExportDestination.CSV
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RegisterExportEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String =
        "RegisterExportEntity(id=$id, scope=$scope, destination=$destination, completed=$completed)"
}
