package com.granatum.core.service

import com.granatum.core.domain.exception.InvoiceNotFoundException
import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.model.Invoice
import com.granatum.core.infrastructure.database.toDomain
import com.granatum.core.infrastructure.database.entities.InvoiceRecognitionEntity
import com.granatum.core.infrastructure.database.repositories.InvoiceRecognitionRepository
import com.granatum.core.infrastructure.database.repositories.InvoiceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Reading one invoice, its state and its last recognition. */
@Service
class InvoiceReader(
    private val invoices: InvoiceRepository,
    private val recognitions: InvoiceRecognitionRepository
) {
    @Transactional(readOnly = true)
    fun invoice(id: UUID): Invoice =
        invoices.findById(id).map { it.toDomain() }.orElseThrow { InvoiceNotFoundException(id) }

    @Transactional(readOnly = true)
    fun status(id: UUID): InvoiceStatus =
        invoices.findById(id).map { it.status }.orElseThrow { InvoiceNotFoundException(id) }

    @Transactional(readOnly = true)
    fun lastRecognition(id: UUID): InvoiceRecognitionEntity? =
        recognitions.findFirstByInvoiceIdOrderByCreatedAtDesc(id)
}
