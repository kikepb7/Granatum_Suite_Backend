package com.granatum.core.infrastructure.database

import com.granatum.core.domain.exception.InvoiceNotFoundException
import com.granatum.core.domain.port.InvoiceDocumentStore
import com.granatum.core.domain.port.StoredInvoiceDocument
import com.granatum.core.infrastructure.database.entities.InvoiceDocumentEntity
import com.granatum.core.infrastructure.database.repositories.InvoiceDocumentRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Originals in `invoice_documents`, in the same transaction as their invoice (research.md D-008). */
@Component
class PostgresInvoiceDocumentStore(private val documents: InvoiceDocumentRepository) : InvoiceDocumentStore {

    override fun save(invoiceId: UUID, content: ByteArray, mediaType: String, originalName: String?) {
        documents.save(InvoiceDocumentEntity(invoiceId, content, mediaType, content.size, originalName?.take(255)))
    }

    @Transactional(readOnly = true)
    override fun read(invoiceId: UUID): StoredInvoiceDocument =
        documents.findById(invoiceId)
            .map { StoredInvoiceDocument(it.content, it.mediaType) }
            .orElseThrow { InvoiceNotFoundException(invoiceId) }
}
