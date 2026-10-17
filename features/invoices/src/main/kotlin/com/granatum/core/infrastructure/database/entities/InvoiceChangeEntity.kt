package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** A change to an invoice already confirmed, with the whole invoice as it was (FR-016, FR-017). Append-only. */
@Entity
@Table(name = "invoice_changes")
class InvoiceChangeEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "invoice_id", nullable = false)
    val invoiceId: UUID,

    @Column(name = "action", nullable = false, length = 20)
    val action: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "previous_values", nullable = false)
    val previousValues: String,

    @Column(name = "author_id", nullable = false)
    val authorId: UUID,

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant
) {
    override fun equals(other: Any?): Boolean = this === other || (other is InvoiceChangeEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "InvoiceChangeEntity(id=$id, action=$action)"

    companion object {
        const val CORRECTION = "CORRECTION"
        const val DISCARD = "DISCARD"
        const val RECLASSIFICATION = "RECLASSIFICATION"
    }
}
