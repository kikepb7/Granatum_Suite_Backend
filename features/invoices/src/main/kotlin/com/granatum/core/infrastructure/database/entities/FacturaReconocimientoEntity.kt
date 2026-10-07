package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** One recognition attempt, as it came back (FR-005, research.md D-009). Append-only. */
@Entity
@Table(name = "factura_reconocimientos")
class FacturaReconocimientoEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "factura_id", nullable = false)
    val facturaId: UUID,

    @Column(name = "resultado", nullable = false, length = 20)
    val resultado: String,

    @Column(name = "modelo", nullable = false, length = 60)
    val modelo: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "propuesta")
    val propuesta: String?,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "campos_dudosos")
    val camposDudosos: String?,

    @Column(name = "tokens_entrada")
    val tokensEntrada: Int?,

    @Column(name = "tokens_salida")
    val tokensSalida: Int?,

    /** The kind of failure, never anything from the document. */
    @Column(name = "error", length = 200)
    val error: String?,

    @Column(name = "creado_en", nullable = false)
    val creadoEn: Instant
) {
    override fun equals(other: Any?): Boolean = this === other || (other is FacturaReconocimientoEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "FacturaReconocimientoEntity(id=$id, resultado=$resultado)"
}
