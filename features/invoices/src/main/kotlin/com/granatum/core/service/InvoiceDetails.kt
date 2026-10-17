package com.granatum.core.service

import com.fasterxml.jackson.module.kotlin.readValue
import com.granatum.core.api.dto.InvoiceDto
import com.granatum.core.api.dto.InvoiceRecognitionSummaryDto
import com.granatum.core.api.dto.toDto
import com.granatum.core.infrastructure.database.InvoicingJson
import org.springframework.stereotype.Service
import java.util.UUID

/** The whole picture of one invoice, as `GET …/invoices/{id}` returns it. */
@Service
class InvoiceDetails(
    private val reader: InvoiceReader,
    private val review: InvoiceReview,
    private val quarters: QuarterLock,
    private val json: InvoicingJson
) {
    fun build(id: UUID): InvoiceDto {
        val invoice = reader.invoice(id)
        val last = reader.lastRecognition(id)
        val summary = last?.let {
            InvoiceRecognitionSummaryDto(it.result, it.uncertainFields?.let { c -> json.mapper.readValue<List<String>>(c) } ?: emptyList())
        }
        return invoice.toDto(
            quarterClosed = invoice.issueDate?.let(quarters::isClosed) ?: false,
            recognition = summary,
            notices = review.noticesOf(id)
        )
    }
}
