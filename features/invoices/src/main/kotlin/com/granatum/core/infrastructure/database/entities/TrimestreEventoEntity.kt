package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** A close or a reopening of a quarter. Append-only; every field a `val`. */
@Entity
@Table(name = "trimestre_eventos")
class TrimestreEventoEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "anio", nullable = false)
    val anio: Short,

    @Column(name = "trimestre", nullable = false)
    val trimestre: Short,

    @Column(name = "accion", nullable = false, length = 10)
    val accion: String,

    @Column(name = "motivo", length = 500)
    val motivo: String?,

    /** Snapshot of the report totals at close time; null on a reopening. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "totales")
    val totales: String?,

    @Column(name = "autor_id", nullable = false)
    val autorId: UUID,

    @Column(name = "ocurrido_en", nullable = false)
    val ocurridoEn: Instant
) {
    override fun equals(other: Any?): Boolean = this === other || (other is TrimestreEventoEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "TrimestreEventoEntity(id=$id, accion=$accion)"

    companion object {
        const val CIERRE = "CIERRE"
        const val REAPERTURA = "REAPERTURA"
    }
}
