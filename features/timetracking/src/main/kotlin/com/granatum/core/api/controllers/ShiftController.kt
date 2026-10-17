package com.granatum.core.api.controllers

import com.granatum.core.api.dto.ClockInRequest
import com.granatum.core.api.dto.ShiftDto
import com.granatum.core.api.dto.BreakEndRequest
import com.granatum.core.api.dto.BreakStartRequest
import com.granatum.core.api.dto.MonthlyShiftSummaryDto
import com.granatum.core.api.dto.ClockOutRequest
import com.granatum.core.api.dto.ShiftLocationDto
import com.granatum.core.api.mappers.toDto
import com.granatum.core.api.util.requestUserId
import java.time.LocalDate
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.GetMapping
import com.granatum.core.domain.type.Role
import com.granatum.core.api.util.requestUserRole
import com.granatum.core.domain.type.ShiftOperationType
import com.granatum.core.service.ShiftOperationHandler
import com.granatum.core.service.ShiftService
import com.granatum.core.service.ShiftLocationInput
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * HTTP entry point for the working-day lifecycle. No business logic: validates
 * with `@Valid` and delegates.
 *
 * There is deliberately **no** `PUT` or `PATCH` on `/api/shifts/{id}`, and
 * none must be added. A finalised fichaje changes only through an approved
 * correction (constitution principle III), and a general update endpoint is
 * precisely how that rule would be broken without anyone noticing.
 *
 * The employee is taken from [requestUserId] - the JWT subject - and never from
 * the request body. A body-supplied id is how "only your own fichajes" gets
 * bypassed.
 */
@RestController
@RequestMapping("/api/shifts")
class ShiftController(
    private val shiftService: ShiftService,
    private val handler: ShiftOperationHandler
) {

    @PostMapping("/clock-in")
    @ResponseStatus(HttpStatus.CREATED)
    fun clockIn(@Valid @RequestBody request: ClockInRequest): ShiftDto =
        handler.run(
            clientEventId = request.clientEventId,
            employeeId = requestUserId,
            operationType = ShiftOperationType.CLOCK_IN,
            occurredAt = request.occurredAt,
            responseStatus = HttpStatus.CREATED.value()
        ) {
            shiftService.clockIn(
                employeeId = requestUserId,
                occurredAt = request.occurredAt,
                location = request.location?.toInput()
            ).toDto()
        }

    @PostMapping("/{id}/break/start")
    fun startBreak(
        @PathVariable id: UUID,
        @Valid @RequestBody request: BreakStartRequest
    ): ShiftDto =
        handler.run(
            clientEventId = request.clientEventId,
            employeeId = requestUserId,
            operationType = ShiftOperationType.BREAK_START,
            occurredAt = request.occurredAt,
            responseStatus = HttpStatus.OK.value()
        ) {
            shiftService.startBreak(
                shiftId = id,
                employeeId = requestUserId,
                occurredAt = request.occurredAt,
                type = request.type
            ).toDto()
        }

    @PostMapping("/{id}/break/end")
    fun endBreak(
        @PathVariable id: UUID,
        @Valid @RequestBody request: BreakEndRequest
    ): ShiftDto =
        handler.run(
            clientEventId = request.clientEventId,
            employeeId = requestUserId,
            operationType = ShiftOperationType.BREAK_END,
            occurredAt = request.occurredAt,
            responseStatus = HttpStatus.OK.value()
        ) {
            shiftService.endBreak(
                shiftId = id,
                employeeId = requestUserId,
                occurredAt = request.occurredAt
            ).toDto()
        }

    @PostMapping("/{id}/clock-out")
    fun clockOut(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ClockOutRequest
    ): ShiftDto =
        handler.run(
            clientEventId = request.clientEventId,
            employeeId = requestUserId,
            operationType = ShiftOperationType.CLOCK_OUT,
            occurredAt = request.occurredAt,
            responseStatus = HttpStatus.OK.value()
        ) {
            shiftService.clockOut(
                shiftId = id,
                employeeId = requestUserId,
                occurredAt = request.occurredAt,
                location = request.location?.toInput()
            ).toDto()
        }

    @GetMapping("/employee/{employeeId}")
    fun byEmployeeAndRange(
        @PathVariable employeeId: UUID,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate
    ): List<ShiftDto> =
        shiftService.findByEmployeeAndRange(
            requestedEmployeeId = employeeId,
            requesterId = requestUserId,
            role = requestUserRole,
            from = from,
            to = to
        ).map { it.toDto(includeLocation = canSeeLocation()) }

    /** Whole workforce. MANAGER, ADMIN and REPRESENTATIVE; never an EMPLOYEE. */
    @GetMapping
    fun allInRange(
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate
    ): List<ShiftDto> =
        shiftService.findAllInRange(
            role = requestUserRole,
            from = from,
            to = to
        ).map { it.toDto(includeLocation = canSeeLocation()) }

    /**
     * A month of someone's register, aggregated (FR-032 to FR-035).
     *
     * This is the calculation only. Downloading it as a file - and handing it to
     * part-time staff with their payslip, as article 12.4.c requires - belongs
     * to the export feature.
     */
    @GetMapping("/employee/{employeeId}/summary")
    fun monthlySummary(
        @PathVariable employeeId: UUID,
        @RequestParam year: Int,
        @RequestParam month: Int
    ): MonthlyShiftSummaryDto =
        shiftService.monthlySummary(
            requestedEmployeeId = employeeId,
            requesterId = requestUserId,
            role = requestUserRole,
            year = year,
            month = month
        ).toDto()

    /**
     * REPRESENTATIVE never sees where someone clocked in (FR-023b).
     *
     * Article 34.9 entitles worker representatives to the register, not to each
     * person's whereabouts, and location is the most intrusive datum in it:
     * handing it over would be processing personal data with no obligation
     * behind it.
     *
     * Decided here rather than in the service because it is a presentation
     * concern - what this caller may be shown - and the service already returns
     * the full model to callers who are entitled to it.
     */
    private fun canSeeLocation(): Boolean = requestUserRole != Role.REPRESENTATIVE

    private fun ShiftLocationDto.toInput() = ShiftLocationInput(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters
    )
}
