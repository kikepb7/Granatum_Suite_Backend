package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.model.InvoiceType
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * An invoice: its data, its state and who moved it between states.
 *
 * The original file is not here but in [InvoiceDocumentEntity], so listing
 * invoices never reads megabytes. Amounts are `BigDecimal` on `NUMERIC(12,2)`:
 * exact to the cent (research.md D-013). `@Version` keeps two simultaneous
 * reviews from silently overwriting each other.
 */
@Entity
@Table(name = "invoices")
class InvoiceEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: InvoiceStatus = InvoiceStatus.PENDING_RECOGNITION,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 10)
    var type: InvoiceType? = null,

    @Column(name = "issuer_name", length = 200)
    var issuerName: String? = null,

    @Column(name = "issuer_nif", length = 20)
    var issuerNif: String? = null,

    @Column(name = "issuer_normalized_nif", length = 20)
    var issuerNormalizedNif: String? = null,

    @Column(name = "recipient_name", length = 200)
    var recipientName: String? = null,

    @Column(name = "recipient_nif", length = 20)
    var recipientNif: String? = null,

    @Column(name = "number", length = 60)
    var number: String? = null,

    @Column(name = "issue_date")
    var issueDate: LocalDate? = null,

    @Column(name = "concept", length = 500)
    var concept: String? = null,

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "bpchar(3)")
    var currency: String = "EUR",

    @Column(name = "withholdings", nullable = false, precision = 12, scale = 2)
    var withholdings: BigDecimal = BigDecimal.ZERO.setScale(2),

    @Column(name = "total", precision = 12, scale = 2)
    var total: BigDecimal? = null,

    @Column(name = "corrective", nullable = false)
    var corrective: Boolean = false,

    @Column(name = "document_sha256", nullable = false, length = 64, updatable = false)
    val documentSha256: String,

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    val uploadedBy: UUID,

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    val uploadedAt: Instant,

    @Column(name = "confirmed_by")
    var confirmedBy: UUID? = null,

    @Column(name = "confirmed_at")
    var confirmedAt: Instant? = null,

    @Column(name = "discarded_by")
    var discardedBy: UUID? = null,

    @Column(name = "discarded_at")
    var discardedAt: Instant? = null
) {
    @Version
    @Column(name = "version", nullable = false)
    var version: Int = 0
        protected set

    /** The whole breakdown is replaced on every correction (contracts/README.md, PUT). */
    @OneToMany(mappedBy = "invoice", fetch = FetchType.LAZY, cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    var vatLines: MutableList<InvoiceVatLineEntity> = mutableListOf()
        protected set

    fun replaceVatLines(newLines: List<InvoiceVatLineEntity>) {
        vatLines.clear()
        newLines.forEachIndexed { i, line ->
            line.invoice = this
            line.sortOrder = i.toShort()
            vatLines.add(line)
        }
    }

    override fun equals(other: Any?): Boolean = this === other || (other is InvoiceEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "InvoiceEntity(id=$id, status=$status)"
}
