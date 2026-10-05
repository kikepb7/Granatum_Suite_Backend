package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.EstadoSolicitud
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

/**
 * A request to change a finalised fichaje. The **only** path by which such a
 * fichaje may change value (constitution, principle III).
 *
 * [valoresPropuestos] and [valoresOriginales] are JSONB documents: immutable,
 * written once, read whole, never queried field by field. [valoresOriginales]
 * is filled in at approval time with the state immediately before applying the
 * change - that is what keeps the original recoverable.
 *
 * No `updated_at`: once resolved, this row does not change again.
 */
@Entity
@Table(name = "solicitudes_correccion_fichaje")
@EntityListeners(AuditingEntityListener::class)
class SolicitudCorreccionFichajeEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fichaje_id", nullable = false)
    var fichaje: FichajeEntity,

    @Column(name = "solicitante_id", nullable = false)
    val solicitanteId: UUID,

    @Column(nullable = false, length = 500)
    val motivo: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "valores_propuestos", nullable = false)
    val valoresPropuestos: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    var estado: EstadoSolicitud = EstadoSolicitud.PENDIENTE,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "valores_originales")
    var valoresOriginales: String? = null,

    @Column(name = "resuelta_por_id")
    var resueltaPorId: UUID? = null,

    @Column(name = "resuelta_en")
    var resueltaEn: Instant? = null,

    @Column(name = "motivo_resolucion", length = 500)
    var motivoResolucion: String? = null
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SolicitudCorreccionFichajeEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    /** No `motivo` and no JSONB payloads: both may carry personal detail. */
    override fun toString(): String =
        "SolicitudCorreccionFichajeEntity(id=$id, estado=$estado)"
}
