package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Audit of one run of the four-year retention purge (constitution, principle
 * III since v2.0.0). Append-only, and never itself purged.
 *
 * Holds **no personal data** on purpose: only counts and a cut-off date. A
 * purge log carrying employee ids or specific shift dates would itself outlive
 * the retention period the purge exists to honour, defeating its own purpose.
 * Counts and a date are enough to show an inspection what was destroyed and
 * when.
 */
@Entity
@Table(name = "retention_purges")
class RetentionPurgeEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "executed_at", nullable = false)
    val executedAt: Instant,

    /** Everything with an entry date on or before this was eligible. */
    @Column(name = "cutoff_date", nullable = false)
    val cutoffDate: LocalDate,

    @Column(name = "deleted_shifts", nullable = false)
    val deletedShifts: Int,

    @Column(name = "deleted_breaks", nullable = false)
    val deletedBreaks: Int,

    @Column(name = "deleted_events", nullable = false)
    val deletedEvents: Int,

    @Column(name = "deleted_requests", nullable = false)
    val deletedRequests: Int,

    /** Feature 003 (V16). Zero for runs from before the export log existed, which is true. */
    @Column(name = "deleted_exports", nullable = false)
    val deletedExports: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RetentionPurgeEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String =
        "RetentionPurgeEntity(id=$id, cutoffDate=$cutoffDate)"
}
