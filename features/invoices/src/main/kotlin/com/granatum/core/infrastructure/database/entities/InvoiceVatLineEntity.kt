package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.model.NoVatCause
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.UUID

/** One VAT rate of an invoice (FR-003, research.md D-020). */
@Entity
@Table(name = "invoice_vat_lines")
class InvoiceVatLineEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 2)
    var vatRate: BigDecimal,

    @Column(name = "base", nullable = false, precision = 12, scale = 2)
    var base: BigDecimal,

    @Column(name = "vat_amount", nullable = false, precision = 12, scale = 2)
    var vatAmount: BigDecimal,

    @Column(name = "surcharge", nullable = false, precision = 12, scale = 2)
    var surcharge: BigDecimal = BigDecimal.ZERO.setScale(2),

    @Enumerated(EnumType.STRING)
    @Column(name = "no_vat_cause", length = 30)
    var noVatCause: NoVatCause? = null
) {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    lateinit var invoice: InvoiceEntity

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Short = 0

    override fun equals(other: Any?): Boolean = this === other || (other is InvoiceVatLineEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "InvoiceVatLineEntity(id=$id)"
}
