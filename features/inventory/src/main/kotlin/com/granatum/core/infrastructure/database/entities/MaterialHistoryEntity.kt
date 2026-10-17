package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.MaterialChangeType
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
@Table(name = "material_history")
class MaterialHistoryEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val materialId: UUID,

    @Column(nullable = false)
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val changeType: MaterialChangeType,

    @Column
    val previousValue: String?,

    @Column
    val newValue: String?,

    @Column(nullable = false)
    val reason: String,

    @Column(nullable = false, updatable = false)
    val date: Instant = Instant.now()
)
