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
@Table(name = "factura_documentos")
class FacturaDocumentoEntity(
    @Id
    @Column(name = "factura_id")
    val facturaId: UUID,

    @Column(name = "contenido", nullable = false, updatable = false)
    val contenido: ByteArray,

    @Column(name = "media_type", nullable = false, length = 40, updatable = false)
    val mediaType: String,

    @Column(name = "tamano", nullable = false, updatable = false)
    val tamano: Int,

    /** May contain a person's name: kept for reference, never logged nor sent back. */
    @Column(name = "nombre_original", length = 255, updatable = false)
    val nombreOriginal: String?
) {
    override fun equals(other: Any?): Boolean = this === other || (other is FacturaDocumentoEntity && facturaId == other.facturaId)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "FacturaDocumentoEntity(facturaId=$facturaId, mediaType=$mediaType)"
}
