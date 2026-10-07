package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.TipoCambioHistorial
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Immutable audit trail row. Never updated after insert - corrections are
 * new rows, not edits, so there is no [org.springframework.data.jpa.domain.support.AuditingEntityListener]
 * or `updatedAt` here.
 */
@Entity
@Table(name = "historial_material")
class HistorialMaterialEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val materialId: UUID,

    @Column(nullable = false)
    val usuarioId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val tipoCambio: TipoCambioHistorial,

    @Column
    val valorAnterior: String?,

    @Column
    val valorNuevo: String?,

    @Column(nullable = false)
    val motivo: String,

    @Column(nullable = false, updatable = false)
    val fecha: Instant = Instant.now()
)
