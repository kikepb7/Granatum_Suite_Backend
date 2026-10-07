package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** A change to an invoice already confirmed, with the whole invoice as it was (FR-016, FR-017). Append-only. */
@Entity
@Table(name = "factura_cambios")
class FacturaCambioEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "factura_id", nullable = false)
    val facturaId: UUID,

    @Column(name = "accion", nullable = false, length = 20)
    val accion: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "valores_anteriores", nullable = false)
    val valoresAnteriores: String,

    @Column(name = "autor_id", nullable = false)
    val autorId: UUID,

    @Column(name = "ocurrido_en", nullable = false)
    val ocurridoEn: Instant
) {
    override fun equals(other: Any?): Boolean = this === other || (other is FacturaCambioEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "FacturaCambioEntity(id=$id, accion=$accion)"

    companion object {
        const val CORRECCION = "CORRECCION"
        const val DESCARTE = "DESCARTE"
        const val RECLASIFICACION = "RECLASIFICACION"
    }
}
