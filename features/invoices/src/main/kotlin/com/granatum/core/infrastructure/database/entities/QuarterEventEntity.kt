package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** A close or a reopening of a quarter. Append-only; every field a `val`. */
@Entity
@Table(name = "quarter_events")
class QuarterEventEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "year", nullable = false)
    val year: Short,

    @Column(name = "quarter", nullable = false)
    val quarter: Short,

    @Column(name = "action", nullable = false, length = 10)
    val action: String,

    @Column(name = "reason", length = 500)
    val reason: String?,

    /** Snapshot of the report totals at close time; null on a reopening. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "totals")
    val totals: String?,

    @Column(name = "author_id", nullable = false)
    val authorId: UUID,

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant
) {
    override fun equals(other: Any?): Boolean = this === other || (other is QuarterEventEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "QuarterEventEntity(id=$id, action=$action)"

    companion object {
        const val CLOSE = "CLOSE"
        const val REOPEN = "REOPEN"
    }
}
