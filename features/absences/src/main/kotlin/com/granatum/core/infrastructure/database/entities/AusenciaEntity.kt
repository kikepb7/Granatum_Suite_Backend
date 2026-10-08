package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.model.Ausencia
import com.granatum.core.domain.model.CausaPermiso
import com.granatum.core.domain.model.EstadoAusencia
import com.granatum.core.domain.model.TipoAusencia
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * An absence (feature 007, V22). Mutable only through the transitions below;
 * V22's CHECKs refuse any other combination.
 */
@Entity
@Table(name = "ausencias")
class AusenciaEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "empleado_id", nullable = false, updatable = false)
    val empleadoId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12, updatable = false)
    val tipo: TipoAusencia,

    @Enumerated(EnumType.STRING)
    @Column(length = 24, updatable = false)
    val causa: CausaPermiso?,

    @Column(nullable = false, updatable = false)
    val desde: LocalDate,

    @Column
    var hasta: LocalDate?,

    @Column(length = 500, updatable = false)
    val comentario: String?,

    @Column(name = "solicitada_por", nullable = false, updatable = false)
    val solicitadaPor: UUID,

    @Column(name = "solicitada_en", nullable = false, updatable = false)
    val solicitadaEn: Instant,

    estadoInicial: EstadoAusencia,
    resueltaPorInicial: UUID? = null,
    resueltaEnInicial: Instant? = null
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var estado: EstadoAusencia = estadoInicial
        protected set

    @Column(name = "motivo_rechazo", length = 500)
    var motivoRechazo: String? = null
        protected set

    @Column(name = "resuelta_por")
    var resueltaPor: UUID? = resueltaPorInicial
        protected set

    @Column(name = "resuelta_en")
    var resueltaEn: Instant? = resueltaEnInicial
        protected set

    @Column(name = "cancelada_en")
    var canceladaEn: Instant? = null
        protected set

    @Version
    @Column(nullable = false)
    var version: Int = 0
        protected set

    fun aprobar(por: UUID, ahora: Instant) {
        check(estado == EstadoAusencia.PENDIENTE)
        estado = EstadoAusencia.APROBADA
        resueltaPor = por
        resueltaEn = ahora
    }

    fun rechazar(por: UUID, motivo: String, ahora: Instant) {
        check(estado == EstadoAusencia.PENDIENTE)
        estado = EstadoAusencia.RECHAZADA
        motivoRechazo = motivo
        resueltaPor = por
        resueltaEn = ahora
    }

    fun cancelar(ahora: Instant) {
        check(estado == EstadoAusencia.PENDIENTE || estado == EstadoAusencia.APROBADA)
        estado = EstadoAusencia.CANCELADA
        canceladaEn = ahora
    }

    fun cerrarBaja(fin: LocalDate) {
        check(tipo == TipoAusencia.BAJA_MEDICA && hasta == null)
        hasta = fin
    }

    fun toModel() = Ausencia(
        id, empleadoId, tipo, causa, desde, hasta, estado, comentario, motivoRechazo,
        solicitadaPor, solicitadaEn, resueltaPor, resueltaEn, canceladaEn, version
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AusenciaEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    // No comment, no rejection reason (FR-021).
    override fun toString(): String = "AusenciaEntity(id=$id, tipo=$tipo, estado=$estado)"
}
