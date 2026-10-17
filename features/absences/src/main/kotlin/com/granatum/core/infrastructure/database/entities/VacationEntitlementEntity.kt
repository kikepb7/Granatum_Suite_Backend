package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Embeddable
data class VacationEntitlementId(
    @Column(name = "employee_id", nullable = false)
    val employeeId: UUID = UUID(0, 0),

    @Column(nullable = false)
    val year: Short = 0
) : Serializable

/** A person's holiday entitlement for one year, when it is not the default (feature 007, V22, renamed in V32). */
@Entity
@Table(name = "vacation_entitlements")
class VacationEntitlementEntity(
    @EmbeddedId
    val id: VacationEntitlementId,

    @Column(nullable = false)
    var days: Short,

    @Column(name = "updated_by", nullable = false)
    var updatedBy: UUID,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VacationEntitlementEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String = "VacationEntitlementEntity(id=$id, days=$days)"
}
