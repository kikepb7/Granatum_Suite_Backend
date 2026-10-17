package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * The original file of an invoice, byte for byte as uploaded (FR-002, FR-025).
 * Its own entity so that loading an invoice never loads the file.
 */
@Entity
@Table(name = "invoice_documents")
class InvoiceDocumentEntity(
    @Id
    @Column(name = "invoice_id")
    val invoiceId: UUID,

    @Column(name = "content", nullable = false, updatable = false)
    val content: ByteArray,

    @Column(name = "media_type", nullable = false, length = 40, updatable = false)
    val mediaType: String,

    @Column(name = "size_bytes", nullable = false, updatable = false)
    val sizeBytes: Int,

    /** May contain a person's name: kept for reference, never logged nor sent back. */
    @Column(name = "original_name", length = 255, updatable = false)
    val originalName: String?
) {
    override fun equals(other: Any?): Boolean = this === other || (other is InvoiceDocumentEntity && invoiceId == other.invoiceId)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "InvoiceDocumentEntity(invoiceId=$invoiceId, mediaType=$mediaType)"
}
