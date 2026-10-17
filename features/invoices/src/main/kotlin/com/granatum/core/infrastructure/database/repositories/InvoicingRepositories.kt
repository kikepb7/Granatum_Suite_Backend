package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.CompanyEntity
import com.granatum.core.infrastructure.database.entities.InvoiceChangeEntity
import com.granatum.core.infrastructure.database.entities.InvoiceDocumentEntity
import com.granatum.core.infrastructure.database.entities.InvoiceEntity
import com.granatum.core.infrastructure.database.entities.InvoiceRecognitionEntity
import com.granatum.core.infrastructure.database.entities.QuarterEntity
import com.granatum.core.infrastructure.database.entities.QuarterEventEntity
import com.granatum.core.infrastructure.database.entities.QuarterId
import com.granatum.core.domain.model.InvoiceStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.Optional
import java.util.UUID

// Invoicing repositories (feature 004). Every one extends Repository<T, ID> and
// declares only what its table admits: **no delete anywhere**. Invoices are
// kept at least six years and discarding one does not remove it (FR-015); the
// histories are append-only. NoInvoicingDeletionIT checks this by reflection
// over a fixed list, so adding a delete fails the build.

interface CompanyRepository : Repository<CompanyEntity, Short> {
    fun save(company: CompanyEntity): CompanyEntity
    fun findById(id: Short): Optional<CompanyEntity>
}

interface QuarterRepository : Repository<QuarterEntity, QuarterId> {
    fun save(quarter: QuarterEntity): QuarterEntity
    fun findById(id: QuarterId): Optional<QuarterEntity>
    fun findAllByIdYearOrderByIdQuarter(year: Short): List<QuarterEntity>

    /** Makes sure the row exists before it is locked: a quarter is open until someone closes it. */
    @Modifying
    @Query(
        value = "INSERT INTO quarters (year, quarter, closed) VALUES (:year, :quarter, FALSE) ON CONFLICT DO NOTHING",
        nativeQuery = true
    )
    fun createIfMissing(@Param("year") year: Short, @Param("quarter") quarter: Short): Int

    /**
     * `SELECT … FOR UPDATE` on the quarter (research.md D-016). Taken by every
     * change to an invoice of the quarter and by closing it, so the two cannot
     * interleave: a close waits for a confirmation in flight, and the other way
     * round. Must be called inside the caller's transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM QuarterEntity q WHERE q.id = :id")
    fun lock(@Param("id") id: QuarterId): QuarterEntity
}

interface QuarterEventRepository : Repository<QuarterEventEntity, UUID> {
    fun save(event: QuarterEventEntity): QuarterEventEntity
    fun findAllByYearOrderByOccurredAtAsc(year: Short): List<QuarterEventEntity>
    fun findAllByYearAndQuarterOrderByOccurredAtAsc(year: Short, quarter: Short): List<QuarterEventEntity>
}

interface InvoiceRepository : Repository<InvoiceEntity, UUID> {
    fun save(invoice: InvoiceEntity): InvoiceEntity
    fun saveAndFlush(invoice: InvoiceEntity): InvoiceEntity
    fun findById(id: UUID): Optional<InvoiceEntity>

    /** The live invoice (not discarded) with this exact file, if any: the duplicate check (D-015). */
    fun findFirstByDocumentSha256AndStatusNot(documentSha256: String, status: InvoiceStatus): InvoiceEntity?

    /** Another confirmed invoice with the same issuer, number and date: the logical duplicate (D-015). */
    @Query(
        """
        SELECT count(i) > 0 FROM InvoiceEntity i
         WHERE i.status = com.granatum.core.domain.model.InvoiceStatus.CONFIRMED
           AND i.issuerNormalizedNif = :nif AND i.number = :number AND i.issueDate = :issueDate
           AND i.id <> :exclude
        """
    )
    fun existsConfirmedDuplicate(
        @Param("nif") normalizedNif: String,
        @Param("number") number: String,
        @Param("issueDate") issueDate: java.time.LocalDate,
        @Param("exclude") exclude: UUID
    ): Boolean

    /** The confirmed invoices of a period, with their VAT lines, for its report (FR-018). */
    @Query(
        """
        SELECT DISTINCT i FROM InvoiceEntity i LEFT JOIN FETCH i.vatLines
         WHERE i.status = com.granatum.core.domain.model.InvoiceStatus.CONFIRMED
           AND i.issueDate BETWEEN :fromDate AND :toDate
        """
    )
    fun findConfirmedBetween(@Param("fromDate") from: java.time.LocalDate, @Param("toDate") to: java.time.LocalDate): List<InvoiceEntity>

    /** Not yet confirmed nor discarded, dated in the period: they do not count, and the report says so (FR-021). */
    @Query(
        """
        SELECT count(i) FROM InvoiceEntity i
         WHERE i.status IN (com.granatum.core.domain.model.InvoiceStatus.PENDING_RECOGNITION,
                            com.granatum.core.domain.model.InvoiceStatus.DRAFT)
           AND i.issueDate BETWEEN :fromDate AND :toDate
        """
    )
    fun countPendingBetween(@Param("fromDate") from: java.time.LocalDate, @Param("toDate") to: java.time.LocalDate): Long

    /**
     * FR-024: the list, every filter optional and combinable. Native with explicit
     * casts, because a null parameter in `:p IS NULL` reaches Postgres without a
     * type and is refused (the lesson of feature 003). [party] arrives already
     * escaped for LIKE. Newest first, undated ones (not read yet) before all.
     */
    @Query(
        value = """
        SELECT * FROM invoices i
         WHERE (CAST(:fromDate AS date) IS NULL OR i.issue_date >= CAST(:fromDate AS date))
           AND (CAST(:toDate AS date) IS NULL OR i.issue_date <= CAST(:toDate AS date))
           AND (CAST(:type AS text) IS NULL OR i.type = CAST(:type AS text))
           AND (CAST(:status AS text) IS NULL OR i.status = CAST(:status AS text))
           AND (CAST(:party AS text) IS NULL
                OR i.issuer_name ILIKE CAST(:party AS text) ESCAPE '\'
                OR i.issuer_nif ILIKE CAST(:party AS text) ESCAPE '\'
                OR i.recipient_name ILIKE CAST(:party AS text) ESCAPE '\'
                OR i.recipient_nif ILIKE CAST(:party AS text) ESCAPE '\')
         ORDER BY i.issue_date DESC NULLS FIRST, i.uploaded_at DESC, i.id
         LIMIT :limit OFFSET :offset
        """,
        nativeQuery = true
    )
    fun search(
        @Param("fromDate") from: java.time.LocalDate?,
        @Param("toDate") to: java.time.LocalDate?,
        @Param("type") type: String?,
        @Param("status") status: String?,
        @Param("party") party: String?,
        @Param("limit") limit: Int,
        @Param("offset") offset: Int
    ): List<InvoiceEntity>

    @Query(
        value = """
        SELECT count(*) FROM invoices i
         WHERE (CAST(:fromDate AS date) IS NULL OR i.issue_date >= CAST(:fromDate AS date))
           AND (CAST(:toDate AS date) IS NULL OR i.issue_date <= CAST(:toDate AS date))
           AND (CAST(:type AS text) IS NULL OR i.type = CAST(:type AS text))
           AND (CAST(:status AS text) IS NULL OR i.status = CAST(:status AS text))
           AND (CAST(:party AS text) IS NULL
                OR i.issuer_name ILIKE CAST(:party AS text) ESCAPE '\'
                OR i.issuer_nif ILIKE CAST(:party AS text) ESCAPE '\'
                OR i.recipient_name ILIKE CAST(:party AS text) ESCAPE '\'
                OR i.recipient_nif ILIKE CAST(:party AS text) ESCAPE '\')
        """,
        nativeQuery = true
    )
    fun countMatching(
        @Param("fromDate") from: java.time.LocalDate?,
        @Param("toDate") to: java.time.LocalDate?,
        @Param("type") type: String?,
        @Param("status") status: String?,
        @Param("party") party: String?
    ): Long

    /** Pending invoices uploaded before [before]: the retry job's candidates. */
    @Query("SELECT i.id FROM InvoiceEntity i WHERE i.status = :status AND i.uploadedAt < :before ORDER BY i.uploadedAt")
    fun findIdsByStatusUploadedBefore(
        @Param("status") status: InvoiceStatus,
        @Param("before") before: Instant
    ): List<UUID>
}

interface InvoiceDocumentRepository : Repository<InvoiceDocumentEntity, UUID> {
    fun save(document: InvoiceDocumentEntity): InvoiceDocumentEntity
    fun findById(invoiceId: UUID): Optional<InvoiceDocumentEntity>
}

interface InvoiceRecognitionRepository : Repository<InvoiceRecognitionEntity, UUID> {
    fun save(recognition: InvoiceRecognitionEntity): InvoiceRecognitionEntity
    fun findAllByInvoiceIdOrderByCreatedAtAsc(invoiceId: UUID): List<InvoiceRecognitionEntity>
    fun findFirstByInvoiceIdOrderByCreatedAtDesc(invoiceId: UUID): InvoiceRecognitionEntity?
    fun countByInvoiceId(invoiceId: UUID): Long
}

interface InvoiceChangeRepository : Repository<InvoiceChangeEntity, UUID> {
    fun save(change: InvoiceChangeEntity): InvoiceChangeEntity
    fun findAllByInvoiceIdOrderByOccurredAtAsc(invoiceId: UUID): List<InvoiceChangeEntity>
}
