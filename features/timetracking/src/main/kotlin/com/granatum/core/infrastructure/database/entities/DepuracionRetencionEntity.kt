package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Audit of one run of the four-year retention purge (constitution, principle
 * III since v2.0.0). Append-only, and never itself purged.
 *
 * Holds **no personal data** on purpose: only counts and a cut-off date. A
 * purge log carrying employee ids or specific shift dates would itself outlive
 * the retention period the purge exists to honour, defeating its own purpose.
 * Counts and a date are enough to show an inspection what was destroyed and
 * when.
 */
@Entity
@Table(name = "depuraciones_retencion")
class DepuracionRetencionEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "ejecutada_en", nullable = false)
    val ejecutadaEn: Instant,

    /** Everything with an entry date on or before this was eligible. */
    @Column(name = "fecha_corte", nullable = false)
    val fechaCorte: LocalDate,

    @Column(name = "fichajes_eliminados", nullable = false)
    val fichajesEliminados: Int,

    @Column(name = "pausas_eliminadas", nullable = false)
    val pausasEliminadas: Int,

    @Column(name = "eventos_eliminados", nullable = false)
    val eventosEliminados: Int,

    @Column(name = "solicitudes_eliminadas", nullable = false)
    val solicitudesEliminadas: Int,

    /** Feature 003 (V16). Zero for runs from before the export log existed, which is true. */
    @Column(name = "exportaciones_eliminadas", nullable = false)
    val exportacionesEliminadas: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DepuracionRetencionEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String =
        "DepuracionRetencionEntity(id=$id, fechaCorte=$fechaCorte)"
}
