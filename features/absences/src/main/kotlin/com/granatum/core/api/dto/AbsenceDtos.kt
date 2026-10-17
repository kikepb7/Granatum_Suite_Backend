package com.granatum.core.api.dto

import com.granatum.core.domain.model.Absence
import com.granatum.core.domain.model.AbsenceStatus
import com.granatum.core.domain.model.AbsenceType
import com.granatum.core.domain.model.LeaveCause
import com.granatum.core.domain.model.VacationBalance
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/*
 * Feature 007, specs/007-absences/contracts/README.md. Requests that carry a
 * comment or a reason override toString: free text a person wrote must not end
 * up in a log line (FR-021).
 */

data class NewAbsenceRequest(
    @field:NotNull val type: AbsenceType? = null,
    val cause: LeaveCause? = null,
    @field:NotNull val from: LocalDate? = null,
    @field:NotNull val to: LocalDate? = null,
    @field:Size(max = 500) val comment: String? = null
) {
    override fun toString(): String = "NewAbsenceRequest(type=$type, from=$from, to=$to)"
}

data class AbsenceRecordRequest(
    @field:NotNull val employeeId: UUID? = null,
    @field:NotNull val type: AbsenceType? = null,
    val cause: LeaveCause? = null,
    @field:NotNull val from: LocalDate? = null,
    /** Only a sick leave may leave it empty: it is closed later with the discharge date. */
    val to: LocalDate? = null,
    @field:Size(max = 500) val comment: String? = null
) {
    override fun toString(): String = "AbsenceRecordRequest(employeeId=$employeeId, type=$type, from=$from, to=$to)"
}

data class AbsenceRejectionRequest(
    @field:NotBlank @field:Size(max = 500) val reason: String = ""
) {
    override fun toString(): String = "AbsenceRejectionRequest(reason=***)"
}

data class SickLeaveDischargeRequest(@field:NotNull val to: LocalDate? = null)

data class VacationEntitlementRequest(@field:NotNull @field:Min(0) @field:Max(366) val days: Int? = null)

data class AbsenceResponse(
    val id: UUID,
    val employeeId: UUID,
    val type: AbsenceType,
    val cause: LeaveCause?,
    val from: LocalDate,
    val to: LocalDate?,
    val status: AbsenceStatus,
    val comment: String?,
    val rejectionReason: String?,
    val requestedBy: UUID,
    val requestedAt: Instant,
    val resolvedBy: UUID?,
    val resolvedAt: Instant?,
    val cancelledAt: Instant?
) {
    override fun toString(): String = "AbsenceResponse(id=$id, type=$type, status=$status)"

    companion object {
        fun of(a: Absence) = AbsenceResponse(
            a.id, a.employeeId, a.type, a.cause, a.from, a.to, a.status, a.comment, a.rejectionReason,
            a.requestedBy, a.requestedAt, a.resolvedBy, a.resolvedAt, a.cancelledAt
        )
    }
}

data class VacationBalanceResponse(
    val employeeId: UUID,
    val year: Int,
    val entitlement: Int,
    val approved: Int,
    val pending: Int,
    val available: Int
) {
    companion object {
        fun of(employeeId: UUID, b: VacationBalance) =
            VacationBalanceResponse(employeeId, b.year, b.entitlement, b.approved, b.pending, b.available)
    }
}
