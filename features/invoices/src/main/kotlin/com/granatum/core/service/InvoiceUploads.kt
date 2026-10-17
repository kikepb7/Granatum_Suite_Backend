package com.granatum.core.service

import com.granatum.core.domain.exception.InvoiceStatusNotAllowedException
import com.granatum.core.domain.exception.InvoiceNotFoundException
import com.granatum.core.domain.exception.RecognitionUnavailableException
import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.port.InvoiceDocumentStore
import com.granatum.core.domain.port.InvoiceRecognizer
import com.granatum.core.infrastructure.database.entities.InvoiceEntity
import com.granatum.core.infrastructure.database.repositories.InvoiceRepository
import com.granatum.core.files.FileTypeDetector
import com.granatum.core.infrastructure.documents.RejectedInvoiceDocument
import com.granatum.core.infrastructure.documents.InvoiceDocumentPreparer
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import java.util.UUID

/** One uploaded file: its bytes and, for reference only, its name. */
class UploadedInvoiceFile(val name: String?, val content: ByteArray) {
    override fun toString(): String = "UploadedInvoiceFile(bytes=${content.size})"
}

/**
 * The outcome for one file, identified by its **position** and never by its
 * name, which may be personal and must not travel back or reach a log.
 */
data class InvoiceUploadResult(val file: Int, val result: String, val invoiceId: UUID?)

/**
 * Stores uploaded invoices and hands them to the recognition queue
 * (FR-001, FR-002, FR-014; research.md D-005, D-007, D-015).
 *
 * Each file in **its own** transaction: one refused or duplicated file does not
 * undo the others. The recognition is queued only **after** the commit, and
 * runs outside any transaction (see [RecognitionQueue]).
 */
@Service
class InvoiceUploads(
    private val invoices: InvoiceRepository,
    private val store: InvoiceDocumentStore,
    private val preparer: InvoiceDocumentPreparer,
    private val queue: RecognitionQueue,
    private val recognizer: InvoiceRecognizer,
    transactionManager: PlatformTransactionManager,
    @param:Value("\${invoices.documents.max-bytes-per-file:10485760}") private val maxBytesPerFile: Long,
    private val clock: Clock = Clock.systemUTC()
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun upload(files: List<UploadedInvoiceFile>, author: UUID): List<InvoiceUploadResult> =
        files.mapIndexed { i, file -> uploadOne(i + 1, file, author) }

    private fun uploadOne(position: Int, file: UploadedInvoiceFile, author: UUID): InvoiceUploadResult {
        val content = file.content
        if (content.isEmpty()) return InvoiceUploadResult(position, "EMPTY", null)
        // The per-file limit lives here and not in Spring's multipart settings,
        // so one big file does not fail the whole upload (finding I1).
        if (content.size > maxBytesPerFile) return InvoiceUploadResult(position, "TOO_LARGE", null)
        val type = FileTypeDetector.detect(content) ?: return InvoiceUploadResult(position, "UNSUPPORTED_FORMAT", null)
        try {
            preparer.inspect(content, type)
        } catch (e: RejectedInvoiceDocument) {
            return InvoiceUploadResult(position, e.result, null)
        }

        val fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
        duplicate(fingerprint)?.let { return InvoiceUploadResult(position, "DUPLICATE", it) }

        val id = try {
            transaction.execute {
                val invoice = invoices.saveAndFlush(
                    InvoiceEntity(documentSha256 = fingerprint, uploadedBy = author, uploadedAt = clock.instant())
                )
                store.save(invoice.id, content, type.mediaType, file.name)
                invoice.id
            }!!
        } catch (e: DataIntegrityViolationException) {
            // Another upload of the same file won the race: the unique index
            // decided, and this one is its duplicate (D-015).
            return InvoiceUploadResult(position, "DUPLICATE", duplicate(fingerprint))
        }

        queue.enqueue(id)
        return InvoiceUploadResult(position, "ACCEPTED", id)
    }

    private fun duplicate(fingerprint: String): UUID? =
        transaction.execute { invoices.findFirstByDocumentSha256AndStatusNot(fingerprint, InvoiceStatus.DISCARDED)?.id }

    /** `POST …/recognize`: queue it again (FR-006). */
    fun retry(id: UUID) {
        if (!recognizer.active) throw RecognitionUnavailableException()
        val status = transaction.execute {
            invoices.findById(id).map { it.status }.orElseThrow { InvoiceNotFoundException(id) }
        }!!
        if (status != InvoiceStatus.PENDING_RECOGNITION && status != InvoiceStatus.DRAFT) {
            throw InvoiceStatusNotAllowedException("Solo se reconoce una factura pendiente o en borrador")
        }
        queue.enqueue(id)
    }
}
