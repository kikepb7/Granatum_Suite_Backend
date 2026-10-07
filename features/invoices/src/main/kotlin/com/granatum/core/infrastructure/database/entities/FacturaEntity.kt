package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.model.TipoFactura
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
 * The original file is not here but in [FacturaDocumentoEntity], so listing
 * invoices never reads megabytes. Amounts are `BigDecimal` on `NUMERIC(12,2)`:
 * exact to the cent (research.md D-013). `@Version` keeps two simultaneous
 * reviews from silently overwriting each other.
 */
@Entity
@Table(name = "facturas")
class FacturaEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    var estado: EstadoFactura = EstadoFactura.PENDIENTE_RECONOCER,

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", length = 10)
    var tipo: TipoFactura? = null,

    @Column(name = "emisor_nombre", length = 200)
    var emisorNombre: String? = null,

    @Column(name = "emisor_nif", length = 20)
    var emisorNif: String? = null,

    @Column(name = "emisor_nif_normalizado", length = 20)
    var emisorNifNormalizado: String? = null,

    @Column(name = "destinatario_nombre", length = 200)
    var destinatarioNombre: String? = null,

    @Column(name = "destinatario_nif", length = 20)
    var destinatarioNif: String? = null,

    @Column(name = "numero", length = 60)
    var numero: String? = null,

    @Column(name = "fecha_emision")
    var fechaEmision: LocalDate? = null,

    @Column(name = "concepto", length = 500)
    var concepto: String? = null,

    @Column(name = "moneda", nullable = false, length = 3, columnDefinition = "bpchar(3)")
    var moneda: String = "EUR",

    @Column(name = "retenciones", nullable = false, precision = 12, scale = 2)
    var retenciones: BigDecimal = BigDecimal.ZERO.setScale(2),

    @Column(name = "total", precision = 12, scale = 2)
    var total: BigDecimal? = null,

    @Column(name = "rectificativa", nullable = false)
    var rectificativa: Boolean = false,

    @Column(name = "documento_sha256", nullable = false, length = 64, updatable = false)
    val documentoSha256: String,

    @Column(name = "subida_por", nullable = false, updatable = false)
    val subidaPor: UUID,

    @Column(name = "subida_en", nullable = false, updatable = false)
    val subidaEn: Instant,

    @Column(name = "confirmada_por")
    var confirmadaPor: UUID? = null,

    @Column(name = "confirmada_en")
    var confirmadaEn: Instant? = null,

    @Column(name = "descartada_por")
    var descartadaPor: UUID? = null,

    @Column(name = "descartada_en")
    var descartadaEn: Instant? = null
) {
    @Version
    @Column(name = "version", nullable = false)
    var version: Int = 0
        protected set

    /** The whole breakdown is replaced on every correction (contracts/README.md, PUT). */
    @OneToMany(mappedBy = "factura", fetch = FetchType.LAZY, cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("orden ASC")
    var lineas: MutableList<FacturaLineaIvaEntity> = mutableListOf()
        protected set

    fun reemplazarLineas(nuevas: List<FacturaLineaIvaEntity>) {
        lineas.clear()
        nuevas.forEachIndexed { i, linea ->
            linea.factura = this
            linea.orden = i.toShort()
            lineas.add(linea)
        }
    }

    override fun equals(other: Any?): Boolean = this === other || (other is FacturaEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "FacturaEntity(id=$id, estado=$estado)"
}
