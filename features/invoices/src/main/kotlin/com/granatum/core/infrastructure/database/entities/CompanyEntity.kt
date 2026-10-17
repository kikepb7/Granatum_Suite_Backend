package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** The company's own data: one row, `id = 1` (FR-029). */
@Entity
@Table(name = "companies")
class CompanyEntity(
    @Id
    val id: Short = 1,

    @Column(name = "legal_name", nullable = false, length = 200)
    var legalName: String,

    @Column(name = "nif", nullable = false, length = 20)
    var nif: String,

    @Column(name = "normalized_nif", nullable = false, length = 20)
    var normalizedNif: String,

    @Column(name = "updated_by", nullable = false)
    var updatedBy: UUID,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant
) {
    override fun equals(other: Any?): Boolean = this === other || (other is CompanyEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "CompanyEntity(id=$id)"
}
