package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.model.Absence
import com.granatum.core.domain.model.AbsenceStatus
import com.granatum.core.domain.model.AbsenceType
import com.granatum.core.domain.model.LeaveCause
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
 * An absence (feature 007, V22, renamed in V32). Mutable only through the
 * transitions below; the table's CHECKs refuse any other combination.
 */
@Entity
@Table(name = "absences")
class AbsenceEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "employee_id", nullable = false, updatable = false)
    val employeeId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12, updatable = false)
    val type: AbsenceType,

    @Enumerated(EnumType.STRING)
    @Column(length = 24, updatable = false)
    val cause: LeaveCause?,

    @Column(name = "from_date", nullable = false, updatable = false)
    val from: LocalDate,

    @Column(name = "to_date")
    var to: LocalDate?,

    @Column(length = 500, updatable = false)
    val comment: String?,

    @Column(name = "requested_by", nullable = false, updatable = false)
    val requestedBy: UUID,

    @Column(name = "requested_at", nullable = false, updatable = false)
    val requestedAt: Instant,

    initialStatus: AbsenceStatus,
    initialResolvedBy: UUID? = null,
    initialResolvedAt: Instant? = null
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var status: AbsenceStatus = initialStatus
        protected set

    @Column(name = "rejection_reason", length = 500)
    var rejectionReason: String? = null
        protected set

    @Column(name = "resolved_by")
    var resolvedBy: UUID? = initialResolvedBy
        protected set

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = initialResolvedAt
        protected set

    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null
        protected set

    @Version
    @Column(nullable = false)
    var version: Int = 0
        protected set

    fun approve(by: UUID, now: Instant) {
        check(status == AbsenceStatus.PENDING)
        status = AbsenceStatus.APPROVED
        resolvedBy = by
        resolvedAt = now
    }

    fun reject(by: UUID, reason: String, now: Instant) {
        check(status == AbsenceStatus.PENDING)
        status = AbsenceStatus.REJECTED
        rejectionReason = reason
        resolvedBy = by
        resolvedAt = now
    }

    fun cancel(now: Instant) {
        check(status == AbsenceStatus.PENDING || status == AbsenceStatus.APPROVED)
        status = AbsenceStatus.CANCELLED
        cancelledAt = now
    }

    fun closeSickLeave(end: LocalDate) {
        check(type == AbsenceType.SICK_LEAVE && to == null)
        to = end
    }

    fun toModel() = Absence(
        id, employeeId, type, cause, from, to, status, comment, rejectionReason,
        requestedBy, requestedAt, resolvedBy, resolvedAt, cancelledAt, version
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AbsenceEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    // No comment, no rejection reason (FR-021).
    override fun toString(): String = "AbsenceEntity(id=$id, type=$type, status=$status)"
}
