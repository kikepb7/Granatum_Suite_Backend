package com.granatum.core.service

import com.fasterxml.jackson.module.kotlin.readValue
import com.granatum.core.domain.event.InvoiceSheetChange
import com.granatum.core.domain.exception.CompanyNotConfiguredException
import com.granatum.core.domain.exception.InvoiceStatusNotAllowedException
import com.granatum.core.domain.exception.DuplicateInvoiceException
import com.granatum.core.domain.exception.InconsistentInvoiceException
import com.granatum.core.domain.exception.InvoiceNotFoundException
import com.granatum.core.domain.exception.StaleInvoiceVersionException
import com.granatum.core.domain.model.InvoiceNotice
import com.granatum.core.domain.model.InvoiceNoticeCode
import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.model.Invoice
import com.granatum.core.domain.service.InvoiceClassifier
import com.granatum.core.domain.service.InvoiceValidationContext
import com.granatum.core.domain.service.InvoiceValidator
import com.granatum.core.infrastructure.database.InvoicingJson
import com.granatum.core.infrastructure.database.toDomain
import com.granatum.core.infrastructure.database.entities.InvoiceChangeEntity
import com.granatum.core.infrastructure.database.entities.InvoiceEntity
import com.granatum.core.infrastructure.database.entities.InvoiceVatLineEntity
import com.granatum.core.infrastructure.database.repositories.InvoiceChangeRepository
import com.granatum.core.infrastructure.database.repositories.InvoiceRecognitionRepository
import com.granatum.core.infrastructure.database.repositories.InvoiceRepository
import com.granatum.core.validation.NifValidator
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Reviewing invoices: correcting, confirming, discarding (FR-009 to FR-017).
 *
 * - Nothing is confirmed with a blocking warning ([InvoiceValidator]).
 * - Every change to an invoice of a quarter, and its confirmation, first locks
 *   the quarter's row and refuses if it is closed (research.md D-016, FR-031).
 * - A confirmed invoice is changed only with its previous values recorded
 *   (FR-016), and never left incomplete.
 * - The logical duplicate is checked here for a readable error and refused by
 *   the partial unique index whatever happens here (D-015).
 * - Confirming, and correcting or discarding a confirmed invoice, publish
 *   [InvoiceSheetChange] inside the transaction (feature 012, US3): the
 *   Google Sheets copy queues its write in that same transaction. A draft
 *   publishes nothing, since it is never copied (FR-017).
 */
@Service
class InvoiceReview(
    private val invoices: InvoiceRepository,
    private val changes: InvoiceChangeRepository,
    private val recognitions: InvoiceRecognitionRepository,
    private val company: CompanyService,
    private val quarters: QuarterLock,
    private val probe: InvoiceConcurrencyProbe,
    private val json: InvoicingJson,
    private val events: ApplicationEventPublisher,
    private val clock: Clock = Clock.systemUTC()
) {
    private val madrid = ZoneId.of("Europe/Madrid")

    @Transactional
    fun correct(id: UUID, values: InvoiceValues, version: Int, author: UUID): Invoice {
        val f = load(id, version)
        if (f.status == InvoiceStatus.DISCARDED) throw InvoiceStatusNotAllowedException("Una factura descartada no se corrige")

        if (f.status == InvoiceStatus.CONFIRMED) {
            val before = f.toDomain()
            f.issueDate?.let(quarters::requireOpen)
            values.issueDate?.takeIf { it != f.issueDate }?.let(quarters::requireOpen)
            val updated = apply(f, values).toDomain()
            val blockers = InvoiceValidator.blockers(notices(updated, checkDuplicate = true))
            if (blockers.isNotEmpty()) throw InconsistentInvoiceException(summary(blockers))
            val onlyType = before.copy(type = updated.type, version = 0) == updated.copy(version = 0)
            probe.afterChecks(id)
            recordChange(id, if (onlyType) InvoiceChangeEntity.RECLASSIFICATION else InvoiceChangeEntity.CORRECTION, before, author)
            events.publishEvent(InvoiceSheetChange(id))
        } else {
            apply(f, values)
            if (f.type == null) {
                f.type = company.find()?.let { InvoiceClassifier.classify(it.normalizedNif, f.issuerNif, f.recipientNif) }
            }
            // FR-006: filling a pending invoice in by hand makes it a draft.
            f.status = InvoiceStatus.DRAFT
        }
        return save(f)
    }

    @Transactional
    fun confirm(id: UUID, version: Int, author: UUID): Invoice {
        val f = load(id, version)
        if (f.status != InvoiceStatus.DRAFT) throw InvoiceStatusNotAllowedException("Solo se confirma un borrador")
        val data = company.find() ?: throw CompanyNotConfiguredException()
        if (f.type == null) f.type = InvoiceClassifier.classify(data.normalizedNif, f.issuerNif, f.recipientNif)

        f.issueDate?.let(quarters::requireOpen)
        val invoice = f.toDomain()
        val allNotices = notices(invoice, checkDuplicate = true)
        if (allNotices.any { it.code == InvoiceNoticeCode.DUPLICATE }) throw DuplicateInvoiceException()
        val blockers = InvoiceValidator.blockers(allNotices)
        if (blockers.isNotEmpty()) throw InconsistentInvoiceException(summary(blockers))

        probe.afterChecks(id)

        f.status = InvoiceStatus.CONFIRMED
        f.confirmedBy = author
        f.confirmedAt = clock.instant()
        val confirmed = save(f)
        events.publishEvent(InvoiceSheetChange(id))
        return confirmed
    }

    @Transactional
    fun discard(id: UUID, version: Int, author: UUID): Invoice {
        val f = load(id, version)
        when (f.status) {
            InvoiceStatus.DISCARDED -> throw InvoiceStatusNotAllowedException("La factura ya esta descartada")
            InvoiceStatus.CONFIRMED -> {
                f.issueDate?.let(quarters::requireOpen)
                probe.afterChecks(id)
                recordChange(id, InvoiceChangeEntity.DISCARD, f.toDomain(), author)
                events.publishEvent(InvoiceSheetChange(id))
            }
            else -> Unit
        }
        f.status = InvoiceStatus.DISCARDED
        f.discardedBy = author
        f.discardedAt = clock.instant()
        return save(f)
    }

    /** The warnings shown with an invoice (`GET …/{id}`). */
    @Transactional(readOnly = true)
    fun noticesOf(id: UUID): List<InvoiceNotice> {
        val f = invoices.findById(id).orElseThrow { InvoiceNotFoundException(id) }
        if (f.status == InvoiceStatus.DISCARDED) return emptyList()
        return notices(f.toDomain(), checkDuplicate = f.status != InvoiceStatus.CONFIRMED)
    }

    private fun notices(f: Invoice, checkDuplicate: Boolean): List<InvoiceNotice> {
        val data = company.find()
        val last = recognitions.findFirstByInvoiceIdOrderByCreatedAtDesc(f.id)
        val classifiable = data != null && (f.issuer?.nif != null || f.recipient?.nif != null)
        val ctx = InvoiceValidationContext(
            today = LocalDate.now(clock.withZone(madrid)),
            uncertainFields = if (f.status == InvoiceStatus.CONFIRMED) emptyList()
                else last?.uncertainFields?.let { json.mapper.readValue<List<String>>(it) } ?: emptyList(),
            duplicate = checkDuplicate && duplicate(f),
            quarterClosed = f.issueDate?.let(quarters::isClosed) ?: false,
            recognitionResult = last?.result,
            notCompanyInvoice = classifiable &&
                InvoiceClassifier.classify(data!!.normalizedNif, f.issuer?.nif, f.recipient?.nif) == null
        )
        return InvoiceValidator.validate(f, ctx)
    }

    private fun duplicate(f: Invoice): Boolean {
        val nif = f.issuer?.nif?.let(NifValidator::normalizeTaxId) ?: return false
        val number = f.number ?: return false
        val date = f.issueDate ?: return false
        return invoices.existsConfirmedDuplicate(nif, number, date, f.id)
    }

    private fun load(id: UUID, version: Int): InvoiceEntity {
        val f = invoices.findById(id).orElseThrow { InvoiceNotFoundException(id) }
        if (f.version != version) throw StaleInvoiceVersionException()
        return f
    }

    private fun apply(f: InvoiceEntity, v: InvoiceValues): InvoiceEntity {
        f.type = v.type
        f.issuerName = v.issuerName
        f.issuerNif = v.issuerNif
        f.issuerNormalizedNif = v.issuerNif?.let(NifValidator::normalizeTaxId)
        f.recipientName = v.recipientName
        f.recipientNif = v.recipientNif
        f.number = v.number
        f.issueDate = v.issueDate
        f.concept = v.concept
        f.currency = v.currency
        f.withholdings = v.withholdings
        f.total = v.total
        f.corrective = v.corrective
        f.replaceVatLines(v.vatLines.map { InvoiceVatLineEntity(vatRate = it.vatRate, base = it.base, vatAmount = it.vatAmount, surcharge = it.surcharge, noVatCause = it.noVatCause) })
        return f
    }

    private fun recordChange(id: UUID, action: String, before: Invoice, author: UUID) {
        changes.save(InvoiceChangeEntity(invoiceId = id, action = action, previousValues = json.write(before), authorId = author, occurredAt = clock.instant()))
    }

    private fun save(f: InvoiceEntity): Invoice = try {
        invoices.saveAndFlush(f).toDomain()
    } catch (e: DataIntegrityViolationException) {
        // Only the partial unique index means "duplicate" (D-015). Any other
        // violation is a real error and must surface as one: translating them
        // all once disguised a CHECK failure as a duplicate.
        if (generateSequence(e as Throwable) { it.cause }.any { it.message?.contains("uk_invoices_confirmed") == true }) {
            throw DuplicateInvoiceException()
        }
        throw e
    } catch (e: ObjectOptimisticLockingFailureException) {
        throw StaleInvoiceVersionException()
    }

    private fun summary(blockers: List<InvoiceNotice>) =
        blockers.joinToString("; ") { "${it.field}: ${it.code}" }
}
