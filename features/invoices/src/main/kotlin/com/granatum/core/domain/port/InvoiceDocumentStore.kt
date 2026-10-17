package com.granatum.core.domain.port

import java.util.UUID

/** An original file, exactly as uploaded. */
class StoredInvoiceDocument(val content: ByteArray, val mediaType: String) {
    override fun toString(): String = "StoredInvoiceDocument(mediaType=$mediaType, bytes=${content.size})"
}

/**
 * Where originals live (research.md D-008). Postgres today; an interface so
 * that moving them to object storage (Supabase Storage, if the database plan is
 * too small) is a new implementation, not a change to the services.
 */
interface InvoiceDocumentStore {
    fun save(invoiceId: UUID, content: ByteArray, mediaType: String, originalName: String?)
    fun read(invoiceId: UUID): StoredInvoiceDocument
}
