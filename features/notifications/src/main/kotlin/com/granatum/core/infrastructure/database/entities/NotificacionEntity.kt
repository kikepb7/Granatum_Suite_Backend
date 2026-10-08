package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.event.TipoAviso
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A notice for one recipient (feature 008, V23). Written through the repository's INSERT … ON CONFLICT. */
@Entity
@Table(name = "notificaciones")
class NotificacionEntity(
    @Id
    val id: UUID,

    @Column(name = "destinatario_id", nullable = false)
    val destinatarioId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    val tipo: TipoAviso,

    @Column(name = "referencia_id", nullable = false)
    val referenciaId: UUID,

    @Column(name = "creada_en", nullable = false)
    val creadaEn: Instant,

    @Column(name = "leida_en")
    var leidaEn: Instant?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NotificacionEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String = "NotificacionEntity(id=$id, tipo=$tipo)"
}
