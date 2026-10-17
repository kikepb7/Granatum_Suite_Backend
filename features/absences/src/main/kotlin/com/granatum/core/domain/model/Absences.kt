package com.granatum.core.domain.model

import com.granatum.core.domain.type.EntityId
import java.time.Instant
import java.time.LocalDate

/** Feature 007. Domain model of absences: no JPA, no HTTP. */
enum class AbsenceType {
    VACATION,

    /** Paid leave (art. 37.3 of the Workers' Statute); needs a [LeaveCause]. */
    LEAVE,

    /** Registered by MANAGER or ADMIN; no free text, ever (FR-007). */
    SICK_LEAVE
}

enum class LeaveCause {
    MARRIAGE,
    BIRTH,
    FAMILY_DEATH,
    FAMILY_ILLNESS,
    HOUSE_MOVE,
    PUBLIC_DUTY,
    OTHER
}

enum class AbsenceStatus { PENDING, APPROVED, REJECTED, CANCELLED }

/**
 * An absence. `to` is null only for an open sick leave.
 *
 * `toString` leaves out the comment and the rejection reason: free text a
 * person wrote, which may say more than it should (FR-021).
 */
data class Absence(
    val id: EntityId,
    val employeeId: EntityId,
    val type: AbsenceType,
    val cause: LeaveCause?,
    val from: LocalDate,
    val to: LocalDate?,
    val status: AbsenceStatus,
    val comment: String?,
    val rejectionReason: String?,
    val requestedBy: EntityId,
    val requestedAt: Instant,
    val resolvedBy: EntityId?,
    val resolvedAt: Instant?,
    val cancelledAt: Instant?,
    val version: Int
) {
    /** Whether it still holds its days: pending or approved. */
    val standing: Boolean get() = status == AbsenceStatus.PENDING || status == AbsenceStatus.APPROVED

    override fun toString(): String =
        "Absence(id=$id, employeeId=$employeeId, type=$type, from=$from, to=$to, status=$status)"
}

/** A person's holidays in one year, in calendar days (FR-015). */
data class VacationBalance(
    val year: Int,
    val entitlement: Int,
    val approved: Int,
    val pending: Int
) {
    val available: Int get() = entitlement - approved - pending
}
