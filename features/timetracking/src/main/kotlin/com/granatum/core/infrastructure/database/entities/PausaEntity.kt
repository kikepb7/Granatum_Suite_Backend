package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.TipoPausa
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A break within a fichaje. At most one may be open at a time, enforced by a
 * partial unique index in V8 rather than by a service check alone.
 *
 * `fichaje` is `lateinit` because the owning side is set by
 * [FichajeEntity.anadirPausa], which keeps both ends of the association in
 * step.
 */
@Entity
@Table(name = "pausas")
class PausaEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    var tipo: TipoPausa,

    @Column(nullable = false)
    var inicio: Instant,

    @Column
    var fin: Instant? = null
) {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fichaje_id", nullable = false)
    lateinit var fichaje: FichajeEntity

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PausaEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    /** Does not touch [fichaje]: it is a lazy proxy. */
    override fun toString(): String = "PausaEntity(id=$id, tipo=$tipo)"
}
