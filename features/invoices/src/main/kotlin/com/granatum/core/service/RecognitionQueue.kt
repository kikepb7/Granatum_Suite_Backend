package com.granatum.core.service

import com.granatum.core.domain.model.NoVatCause
import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.model.RecognizedProposal
import com.granatum.core.domain.port.InvoiceDocumentStore
import com.granatum.core.domain.service.InvoiceClassifier
import com.granatum.core.domain.port.InvoiceRecognizer
import com.granatum.core.domain.port.RecognitionResult
import com.granatum.core.infrastructure.database.InvoicingJson
import com.granatum.core.infrastructure.database.entities.InvoiceEntity
import com.granatum.core.infrastructure.database.entities.InvoiceVatLineEntity
import com.granatum.core.infrastructure.database.entities.InvoiceRecognitionEntity
import com.granatum.core.infrastructure.database.repositories.InvoiceRecognitionRepository
import com.granatum.core.infrastructure.database.repositories.InvoiceRepository
import com.granatum.core.infrastructure.documents.InvoiceDocumentPreparer
import com.granatum.core.files.DocumentType
import com.granatum.core.validation.NifValidator
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recognises invoices in the background, a few at a time (research.md D-005).
 *
 * ## Never with a transaction open while Claude answers
 *
 * A call can take tens of seconds. Holding a database connection meanwhile is
 * what exhausted the pool in feature 002 and held one for a stalled client in
 * feature 003. So each invoice goes through three separate steps: a short
 * read-only transaction to fetch the original, the call **with no transaction
 * at all**, and a short new transaction to write the result.
 * `InvoiceUploadsIT` checks that the recogniser never sees one open.
 *
 * At most `concurrency` recognitions at a time, to stay within the API's rate
 * limits; an invoice already in the queue is not queued twice.
 */
@Component
class RecognitionQueue(
    private val recognizer: InvoiceRecognizer,
    private val invoices: InvoiceRepository,
    private val recognitions: InvoiceRecognitionRepository,
    private val store: InvoiceDocumentStore,
    private val preparer: InvoiceDocumentPreparer,
    private val json: InvoicingJson,
    private val company: CompanyService,
    transactionManager: PlatformTransactionManager,
    @param:Value("\${invoices.recognition.concurrency:3}") concurrency: Int,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactions = TransactionTemplate(transactionManager)
    private val inFlight: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val threadNumber = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(concurrency) { task ->
        Thread(task, "invoice-recognition-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
    }

    /** Queues [id] unless recognition is off or it is already queued. Never call it inside a transaction. */
    fun enqueue(id: UUID): Boolean {
        if (!recognizer.active || !inFlight.add(id)) return false
        executor.submit {
            try {
                process(id)
            } catch (e: Exception) {
                // The kind only: an exception message could quote the document.
                log.warn("Reconocimiento de la factura {} fallido: {}", id, e.javaClass.simpleName)
            } finally {
                inFlight.remove(id)
            }
        }
        return true
    }

    private fun process(id: UUID) {
        // 1. Short read: the original, if the invoice can still be recognised.
        val original = transactions.execute {
            val f = invoices.findById(id).orElse(null)
            if (f == null || f.status !in RECOGNIZABLE) null else store.read(id)
        } ?: return

        // 2. The call, with NO transaction (D-005).
        val toSend = preparer.forSending(original.content, DocumentType.fromMediaType(original.mediaType))
        val result = try {
            recognizer.recognize(toSend)
        } catch (e: Exception) {
            RecognitionResult.Failed("INTERNAL_ERROR", "unknown")
        }

        // 3. Short write: the attempt, and the proposal into the draft.
        transactions.executeWithoutResult { save(id, result) }
    }

    private fun save(id: UUID, result: RecognitionResult) {
        val invoice = invoices.findById(id).orElse(null) ?: return
        val (code, proposal, error) = when (result) {
            is RecognitionResult.Recognized -> Triple(
                when {
                    !result.proposal.isInvoice -> "NOT_AN_INVOICE"
                    result.proposal.multipleInvoices -> "MULTIPLE_INVOICES"
                    else -> "RECOGNIZED"
                },
                result.proposal, null
            )
            is RecognitionResult.Refused -> Triple("REFUSED", null, result.category?.let { "REFUSAL:$it" } ?: "REFUSAL")
            is RecognitionResult.Failed -> Triple("ERROR", null, result.type)
        }
        recognitions.save(
            InvoiceRecognitionEntity(
                invoiceId = id,
                result = code,
                model = result.model,
                proposal = proposal?.let(json::write),
                uncertainFields = proposal?.let { json.write(it.uncertainFields) },
                inputTokens = result.inputTokens,
                outputTokens = result.outputTokens,
                error = error?.take(200),
                createdAt = clock.instant()
            )
        )
        // Only an invoice still waiting or in draft takes the proposal: a
        // confirmed or discarded one is the reviewer's, not the model's.
        if (proposal != null && invoice.status in RECOGNIZABLE) apply(invoice, proposal)
    }

    /**
     * Fills the draft with what was proposed. What does not parse stays empty
     * rather than being guessed (FR-004); a non-invoice gets no figures.
     */
    private fun apply(f: InvoiceEntity, p: RecognizedProposal) {
        f.status = InvoiceStatus.DRAFT
        if (!p.isInvoice) {
            f.replaceVatLines(emptyList())
            f.total = null
            return
        }
        f.issuerName = p.issuerName?.take(200)
        f.issuerNif = p.issuerNif?.take(20)
        f.issuerNormalizedNif = p.issuerNif?.let { NifValidator.normalizeTaxId(it).take(20) }
        f.recipientName = p.recipientName?.take(200)
        f.recipientNif = p.recipientNif?.take(20)
        f.number = p.number?.take(60)
        f.issueDate = p.issueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        f.concept = p.concept?.take(500)
        f.currency = p.currency?.uppercase()?.takeIf { it.length == 3 } ?: "EUR"
        f.withholdings = amount(p.withholdings) ?: BigDecimal.ZERO.setScale(2)
        f.total = amount(p.total)
        f.corrective = p.corrective ?: false
        // FR-008: issued or received as soon as the tax ids are known; the
        // reviewer can still change it.
        f.type = company.find()?.let { InvoiceClassifier.classify(it.normalizedNif, f.issuerNif, f.recipientNif) }
        f.replaceVatLines(p.vatLines.mapNotNull { l ->
            val rate = amount(l.vatRate) ?: return@mapNotNull null
            val base = amount(l.base) ?: return@mapNotNull null
            val vatAmount = amount(l.vatAmount) ?: return@mapNotNull null
            InvoiceVatLineEntity(
                vatRate = rate,
                base = base,
                vatAmount = vatAmount,
                surcharge = amount(l.surcharge) ?: BigDecimal.ZERO.setScale(2),
                noVatCause = l.noVatCause?.let { c -> runCatching { NoVatCause.valueOf(c) }.getOrNull() }
            )
        })
    }

    private fun amount(text: String?): BigDecimal? =
        text?.trim()?.let { runCatching { BigDecimal(it).setScale(2, RoundingMode.HALF_UP) }.getOrNull() }

    @PreDestroy
    fun stop() {
        executor.shutdownNow()
    }

    companion object {
        private val RECOGNIZABLE = setOf(InvoiceStatus.PENDING_RECOGNITION, InvoiceStatus.DRAFT)
    }
}
