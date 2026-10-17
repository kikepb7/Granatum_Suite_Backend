package com.granatum.core.api.controllers

import com.granatum.core.api.dto.AbsenceRecordRequest
import com.granatum.core.api.dto.AbsenceRejectionRequest
import com.granatum.core.api.dto.NewAbsenceRequest
import com.granatum.core.api.dto.AbsenceResponse
import com.granatum.core.api.dto.SickLeaveDischargeRequest
import com.granatum.core.api.dto.VacationBalanceResponse
import com.granatum.core.api.dto.VacationEntitlementRequest
import com.granatum.core.api.util.requestUserId
import com.granatum.core.api.util.requestUserRole
import com.granatum.core.domain.model.AbsenceStatus
import com.granatum.core.domain.type.Role
import com.granatum.core.service.AbsenceService
import com.granatum.core.service.NewAbsence
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Absences (feature 007). Which role reaches which route is declared in
 * `SecurityConfig`; whose absences a caller sees is decided here and in the
 * service, always against the token subject - an id in the request never
 * widens what an EMPLOYEE can see (principle IV).
 */
@RestController
@RequestMapping("/api/absences")
class AbsenceController(private val service: AbsenceService) {

    /** MANAGER and ADMIN see everyone's; EMPLOYEE only their own (FR-018). */
    private val seesAll: Boolean get() = requestUserRole == Role.ADMIN || requestUserRole == Role.MANAGER

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun request(@Valid @RequestBody r: NewAbsenceRequest): AbsenceResponse =
        AbsenceResponse.of(service.request(requestUserId, NewAbsence(r.type!!, r.cause, r.from!!, r.to, r.comment)))

    @PostMapping("/record")
    @ResponseStatus(HttpStatus.CREATED)
    fun record(@Valid @RequestBody r: AbsenceRecordRequest): AbsenceResponse =
        AbsenceResponse.of(
            service.record(requestUserId, r.employeeId!!, NewAbsence(r.type!!, r.cause, r.from!!, r.to, r.comment))
        )

    /**
     * The calendar. Without dates, the current year. An EMPLOYEE's `employeeId`
     * is ignored and replaced by their own: asking for someone else's returns
     * one's own, never a 403 that would confirm the other id exists.
     */
    @GetMapping
    fun search(
        @RequestParam(required = false) employeeId: UUID?,
        @RequestParam(required = false) status: AbsenceStatus?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?
    ): List<AbsenceResponse> {
        val year = LocalDate.now(MADRID).year
        val employee = if (seesAll) employeeId else requestUserId
        return service.search(employee, status, from ?: LocalDate.of(year, 1, 1), to ?: LocalDate.of(year, 12, 31))
            .map(AbsenceResponse::of)
    }

    @GetMapping("/balance")
    fun balance(
        @RequestParam(required = false) year: Int?,
        @RequestParam(required = false) employeeId: UUID?
    ): VacationBalanceResponse {
        val employee = if (seesAll) employeeId ?: requestUserId else requestUserId
        val balanceYear = year ?: LocalDate.now(MADRID).year
        return VacationBalanceResponse.of(employee, service.balance(employee, balanceYear))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): AbsenceResponse =
        AbsenceResponse.of(service.get(id, requestUserId, seesAll))

    @PostMapping("/{id}/approve")
    fun approve(@PathVariable id: UUID): AbsenceResponse = AbsenceResponse.of(service.approve(id, requestUserId))

    @PostMapping("/{id}/reject")
    fun reject(@PathVariable id: UUID, @Valid @RequestBody r: AbsenceRejectionRequest): AbsenceResponse =
        AbsenceResponse.of(service.reject(id, requestUserId, r.reason))

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: UUID): AbsenceResponse = AbsenceResponse.of(service.cancel(id, requestUserId))

    @PostMapping("/{id}/discharge")
    fun discharge(@PathVariable id: UUID, @Valid @RequestBody r: SickLeaveDischargeRequest): AbsenceResponse =
        AbsenceResponse.of(service.discharge(id, requestUserId, r.to!!))

    @PutMapping("/entitlements/{employeeId}/{year}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun setEntitlement(
        @PathVariable employeeId: UUID,
        @PathVariable year: Int,
        @Valid @RequestBody r: VacationEntitlementRequest
    ) {
        service.setEntitlement(employeeId, year, r.days!!, requestUserId)
    }

    companion object {
        private val MADRID: ZoneId = ZoneId.of("Europe/Madrid")
    }
}
