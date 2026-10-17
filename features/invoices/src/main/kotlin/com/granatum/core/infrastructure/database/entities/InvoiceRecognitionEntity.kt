package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** One recognition attempt, as it came back (FR-005, research.md D-009). Append-only. */
@Entity
@Table(name = "invoice_recognitions")
class InvoiceRecognitionEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "invoice_id", nullable = false)
    val invoiceId: UUID,

    @Column(name = "result", nullable = false, length = 20)
    val result: String,

    @Column(name = "model", nullable = false, length = 60)
    val model: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposal")
    val proposal: String?,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "uncertain_fields")
    val uncertainFields: String?,

    @Column(name = "input_tokens")
    val inputTokens: Int?,

    @Column(name = "output_tokens")
    val outputTokens: Int?,

    /** The kind of failure, never anything from the document. */
    @Column(name = "error", length = 200)
    val error: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant
) {
    override fun equals(other: Any?): Boolean = this === other || (other is InvoiceRecognitionEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "InvoiceRecognitionEntity(id=$id, result=$result)"
}
