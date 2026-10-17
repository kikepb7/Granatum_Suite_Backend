package com.granatum.core.service

import com.fasterxml.jackson.module.kotlin.readValue
import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.model.InvoiceType
import com.granatum.core.domain.port.InvoiceDocumentStore
import com.granatum.core.domain.port.StoredInvoiceDocument
import com.granatum.core.infrastructure.database.InvoicingJson
import com.granatum.core.infrastructure.database.toDomain
import com.granatum.core.infrastructure.database.repositories.InvoiceChangeRepository
import com.granatum.core.infrastructure.database.repositories.InvoiceRecognitionRepository
import com.granatum.core.infrastructure.database.repositories.InvoiceRepository
import com.granatum.core.files.DocumentType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class InvoiceFilter(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val party: String? = null,
    val type: InvoiceType? = null,
    val status: InvoiceStatus? = null,
    val page: Int = 0,
    val size: Int = 50
) {
    override fun toString(): String = "InvoiceFilter(page=$page)"
}

data class InvoiceSummary(
    val id: UUID,
    val status: InvoiceStatus,
    val type: InvoiceType?,
    val issuerName: String?,
    val issuerNif: String?,
    val recipientName: String?,
    val recipientNif: String?,
    val number: String?,
    val issueDate: LocalDate?,
    val total: BigDecimal?,
    val noticeCount: Int
) {
    override fun toString(): String = "InvoiceSummary(id=$id, status=$status)"
}

data class InvoicePage<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)

data class RecognitionHistoryEntry(
    val result: String,
    val model: String,
    val createdAt: Instant,
    /** Plain maps and lists, so the web layer's Jackson 3 can write it. */
    val proposal: Any?,
    val error: String?
) {
    override fun toString(): String = "RecognitionHistoryEntry(result=$result)"
}

data class InvoiceChangeHistoryEntry(val action: String, val authorId: UUID, val occurredAt: Instant, val previousValues: Any?) {
    override fun toString(): String = "InvoiceChangeHistoryEntry(action=$action)"
}

data class InvoiceHistory(val recognitions: List<RecognitionHistoryEntry>, val changes: List<InvoiceChangeHistoryEntry>)

class InvoiceOriginalDownload(val document: StoredInvoiceDocument, val name: String)

/** Finding invoices and justifying any figure with its document and history (FR-024, FR-025, FR-005, FR-017). */
@Service
class InvoiceQueries(
    private val invoices: InvoiceRepository,
    private val recognitions: InvoiceRecognitionRepository,
    private val changes: InvoiceChangeRepository,
    private val store: InvoiceDocumentStore,
    private val review: InvoiceReview,
    private val reader: InvoiceReader,
    private val json: InvoicingJson
) {
    @Transactional(readOnly = true)
    fun list(f: InvoiceFilter): InvoicePage<InvoiceSummary> {
        val size = f.size.coerceIn(1, 200)
        val page = f.page.coerceAtLeast(0)
        // `%` and `_` are text here, not wildcards.
        val party = f.party?.trim()?.takeIf { it.isNotEmpty() }
            ?.replace("\\", "\\\\")?.replace("%", "\\%")?.replace("_", "\\_")?.let { "%$it%" }
        val rows = invoices.search(f.from, f.to, f.type?.name, f.status?.name, party, size, page * size)
        val total = invoices.countMatching(f.from, f.to, f.type?.name, f.status?.name, party)
        return InvoicePage(
            rows.map {
                val d = it.toDomain()
                InvoiceSummary(d.id, d.status, d.type, d.issuer?.name, d.issuer?.nif, d.recipient?.name,
                    d.recipient?.nif, d.number, d.issueDate, d.total, review.noticesOf(d.id).size)
            },
            page, size, total
        )
    }

    /** The original exactly as uploaded; named by id, never by its original name (D-017 of feature 003). */
    fun original(id: UUID): InvoiceOriginalDownload {
        reader.status(id)
        val document = store.read(id)
        return InvoiceOriginalDownload(document, "factura-$id.${DocumentType.fromMediaType(document.mediaType).extension}")
    }

    @Transactional(readOnly = true)
    fun history(id: UUID): InvoiceHistory {
        reader.status(id)
        return InvoiceHistory(
            recognitions.findAllByInvoiceIdOrderByCreatedAtAsc(id).map {
                RecognitionHistoryEntry(it.result, it.model, it.createdAt, it.proposal?.let { p -> json.mapper.readValue<Map<String, Any?>>(p) }, it.error)
            },
            changes.findAllByInvoiceIdOrderByOccurredAtAsc(id).map {
                InvoiceChangeHistoryEntry(it.action, it.authorId, it.occurredAt, json.mapper.readValue<Map<String, Any?>>(it.previousValues))
            }
        )
    }
}
