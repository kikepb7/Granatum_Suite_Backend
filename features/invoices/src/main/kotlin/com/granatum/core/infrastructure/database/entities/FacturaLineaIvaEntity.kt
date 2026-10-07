package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.model.CausaSinCuota
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
@Table(name = "factura_lineas_iva")
class FacturaLineaIvaEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "tipo_iva", nullable = false, precision = 5, scale = 2)
    var tipoIva: BigDecimal,

    @Column(name = "base", nullable = false, precision = 12, scale = 2)
    var base: BigDecimal,

    @Column(name = "cuota", nullable = false, precision = 12, scale = 2)
    var cuota: BigDecimal,

    @Column(name = "recargo", nullable = false, precision = 12, scale = 2)
    var recargo: BigDecimal = BigDecimal.ZERO.setScale(2),

    @Enumerated(EnumType.STRING)
    @Column(name = "causa_sin_cuota", length = 30)
    var causaSinCuota: CausaSinCuota? = null
) {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "factura_id", nullable = false)
    lateinit var factura: FacturaEntity

    @Column(name = "orden", nullable = false)
    var orden: Short = 0

    override fun equals(other: Any?): Boolean = this === other || (other is FacturaLineaIvaEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "FacturaLineaIvaEntity(id=$id)"
}
