package com.granatum.core.service

import com.granatum.core.api.util.CivilDateRange
import com.granatum.core.domain.type.Role
import java.time.LocalDate
import com.granatum.core.domain.exception.EmployeeInactiveException
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.EmployeeNotFoundException
import com.granatum.core.domain.exception.ShiftNotInProgressException
import com.granatum.core.domain.exception.ShiftNotFoundException
import com.granatum.core.domain.exception.ShiftAlreadyInProgressException
import com.granatum.core.domain.exception.BreakOpenOnCloseException
import com.granatum.core.domain.exception.BreakNotOpenException
import com.granatum.core.domain.exception.BreakAlreadyOpenException
import com.granatum.core.domain.exception.InconsistentValuesException
import com.granatum.core.domain.model.MonthlyShiftSummary
import com.granatum.core.domain.service.MonthlySummaryCalculator
import com.granatum.core.domain.type.CorrectionStatus
import com.granatum.core.infrastructure.database.repositories.ShiftCorrectionRequestRepository
import com.granatum.core.domain.model.ShiftModel
import com.granatum.core.domain.service.WorkedTimeCalculator
import com.granatum.core.domain.service.WorkedTimeCalculator.BreakInterval
import com.granatum.core.domain.type.ShiftStatus
import com.granatum.core.domain.type.BreakType
import com.granatum.core.infrastructure.database.entities.ShiftEntity
import com.granatum.core.infrastructure.database.entities.ShiftBreakEntity
import com.granatum.core.infrastructure.database.entities.ShiftLocationEmbeddable
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.infrastructure.database.repositories.ShiftRepository
import com.granatum.core.infrastructure.database.repositories.ShiftBreakRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Location as it arrives from the edge, without the HTTP DTO coming along. */
data class ShiftLocationInput(
    val latitude: BigDecimal,
    val longitude: BigDecimal,
    val accuracyMeters: Int?
)

/**
 * Orchestrates the working-day lifecycle.
 *
 * Every method returns a [ShiftModel], never an entity, and maps **inside**
 * the transaction: `spring.jpa.open-in-view` is false, so touching the lazy
 * `breaks` collection after returning would throw.
 *
 * The employee is always the authenticated subject, passed in by the
 * controller from the JWT. It is never read from a request body: accepting an
 * id from the payload is how "only your own fichajes" gets bypassed.
 */
@Service
class ShiftService(
    private val shiftRepository: ShiftRepository,
    private val shiftBreakRepository: ShiftBreakRepository,
    private val employeeRepository: EmployeeRepository,
    private val correctionRepository: ShiftCorrectionRequestRepository
) {

    @Transactional
    fun clockIn(
        employeeId: UUID,
        occurredAt: Instant,
        location: ShiftLocationInput?
    ): ShiftModel {
        val employee = employeeRepository.findById(employeeId)
            .orElseThrow { EmployeeNotFoundException(employeeId) }

        if (!employee.active) throw EmployeeInactiveException()

        // Only IN_PROGRESS blocks a new shift. An INCOMPLETE fichaje dragged in
        // from a previous day must not (FR-012a): the person forgot to clock
        // out, and locking them out of today's shift would turn one mistake
        // into a second, larger gap in the register.
        //
        // The partial unique index is what actually guarantees uniqueness under
        // concurrency - two simultaneous clock-ins both pass this check,
        // because each reads before either writes. This is here to produce a
        // readable error in the ordinary case.
        shiftRepository.findByEmployeeIdAndStatus(employeeId, ShiftStatus.IN_PROGRESS)
            ?.let { throw ShiftAlreadyInProgressException() }

        val shift = shiftRepository.save(
            ShiftEntity(
                employee = employee,
                clockIn = occurredAt,
                status = ShiftStatus.IN_PROGRESS,
                clockInLocation = location?.toEmbeddable()
            )
        )

        return shift.toModel()
    }

    @Transactional
    fun startBreak(
        shiftId: UUID,
        employeeId: UUID,
        occurredAt: Instant,
        type: BreakType
    ): ShiftModel {
        val shift = loadShiftInProgress(shiftId, employeeId)

        if (shift.openBreak() != null) throw BreakAlreadyOpenException()

        shift.addBreak(ShiftBreakEntity(type = type, startedAt = occurredAt))

        return shiftRepository.save(shift).toModel()
    }

    @Transactional
    fun endBreak(
        shiftId: UUID,
        employeeId: UUID,
        occurredAt: Instant
    ): ShiftModel {
        val shift = loadShiftInProgress(shiftId, employeeId)
        val shiftBreak = shift.openBreak() ?: throw BreakNotOpenException()

        if (!occurredAt.isAfter(shiftBreak.startedAt)) {
            throw InconsistentValuesException(
                "el fin de la pausa no es posterior a su inicio"
            )
        }

        shiftBreak.endedAt = occurredAt
        shiftBreakRepository.save(shiftBreak)

        return shiftRepository.save(shift).toModel()
    }

    @Transactional
    fun clockOut(
        shiftId: UUID,
        employeeId: UUID,
        occurredAt: Instant,
        location: ShiftLocationInput?
    ): ShiftModel {
        val shift = loadShiftInProgress(shiftId, employeeId)

        // A shift cannot be closed with a pausa still running: the worked time
        // would be computed against a break that never ended.
        if (shift.openBreak() != null) throw BreakOpenOnCloseException()

        // Throws if the values could not describe a real shift, rather than
        // storing something incoherent in a record that has to stand up to
        // inspection.
        val minutes = WorkedTimeCalculator.workedMinutes(
            clockIn = shift.clockIn,
            clockOut = occurredAt,
            breaks = shift.breaks.map { BreakInterval(it.startedAt, it.endedAt!!) }
        )

        shift.clockOut = occurredAt
        shift.clockOutLocation = location?.toEmbeddable()
        shift.workedMinutes = minutes
        shift.status = ShiftStatus.CLOSED

        return shiftRepository.save(shift).toModel()
    }

    @Transactional(readOnly = true)
    fun findById(shiftId: UUID): ShiftModel =
        shiftRepository.findById(shiftId)
            .orElseThrow { ShiftNotFoundException(shiftId) }
            .toModel()

    /**
     * A person's shifts within a civil date range (FR-021 to FR-023c).
     *
     * [requestedEmployeeId] comes from the request path, and for an EMPLOYEE it
     * is compared against [requesterId] - the JWT subject - and refused if
     * they differ. The path id is there for readability of the API; it is never
     * the source of authority. Checking it the other way round, trusting the
     * path and ignoring the token, is the single mistake that would turn this
     * into a data breach.
     *
     * Mapping happens inside this transaction: `breaks` is lazy and
     * `open-in-view` is false.
     */
    @Transactional(readOnly = true)
    fun findByEmployeeAndRange(
        requestedEmployeeId: UUID,
        requesterId: UUID,
        role: Role,
        from: LocalDate,
        to: LocalDate
    ): List<ShiftModel> {
        // Authorization before input validation, deliberately. Nothing here
        // leaks either way - the range describes the caller's own input - but
        // refusing an unauthorized caller first means no work is done on their
        // behalf and no response varies with what they sent.
        if (role == Role.EMPLOYEE && requestedEmployeeId != requesterId) {
            throw ForbiddenException("Solo puede consultar sus propios fichajes")
        }

        if (to.isBefore(from)) {
            throw InconsistentValuesException("'hasta' es anterior a 'desde'")
        }

        if (!employeeRepository.findById(requestedEmployeeId).isPresent) {
            throw EmployeeNotFoundException(requestedEmployeeId)
        }

        val (start, end) = CivilDateRange.range(from, to)

        // Filtered on the entry instant, so a shift crossing midnight appears
        // on the day it started and not on both.
        return shiftRepository
            .findAllByEmployeeIdAndClockInBetweenOrderByClockInDesc(
                requestedEmployeeId, start, end
            )
            .map { it.toModel() }
    }

    /**
     * Every member of staff's shifts in a range. For MANAGER, ADMIN and
     * REPRESENTATIVE only; an EMPLOYEE has no route to it at all.
     */
    @Transactional(readOnly = true)
    fun findAllInRange(role: Role, from: LocalDate, to: LocalDate): List<ShiftModel> {
        if (role == Role.EMPLOYEE) {
            throw ForbiddenException("Solo puede consultar sus propios fichajes")
        }
        if (to.isBefore(from)) {
            throw InconsistentValuesException("'hasta' es anterior a 'desde'")
        }

        val (start, end) = CivilDateRange.range(from, to)
        return shiftRepository
            .findAllByClockInBetweenOrderByClockInDesc(start, end)
            .map { it.toModel() }
    }

    /**
     * A month of someone's register, aggregated (FR-032 to FR-035).
     *
     * Same visibility rules as the plain listing: an EMPLOYEE only their own,
     * compared against the JWT subject.
     *
     * Returns the **calculation**. Downloading it - the file format and the
     * cadence of handing it over with a payslip - belongs to the export
     * feature; putting CSV generation here would duplicate what that feature
     * owns.
     */
    @Transactional(readOnly = true)
    fun monthlySummary(
        requestedEmployeeId: UUID,
        requesterId: UUID,
        role: Role,
        year: Int,
        month: Int
    ): MonthlyShiftSummary {
        if (month !in 1..12) throw InconsistentValuesException("el mes debe estar entre 1 y 12")

        if (role == Role.EMPLOYEE && requestedEmployeeId != requesterId) {
            throw ForbiddenException("Solo puede consultar sus propios fichajes")
        }

        val employee = employeeRepository.findById(requestedEmployeeId)
            .orElseThrow { EmployeeNotFoundException(requestedEmployeeId) }

        val firstDay = LocalDate.of(year, month, 1)
        val last = firstDay.withDayOfMonth(firstDay.lengthOfMonth())
        val (start, end) = CivilDateRange.range(firstDay, last)

        val shifts = shiftRepository
            .findAllByEmployeeIdAndClockInBetweenOrderByClockInDesc(
                requestedEmployeeId, start, end
            )
            // Spring Data's `Between` includes both ends, and `end` is 00:00 on
            // the first of the next month: a shift starting at exactly that
            // instant - a round time a correction readily sets - belongs to the
            // next month, and counting it here made this total disagree with the
            // monthly download (FR-020 of feature 003, MonthlyDownloadIT).
            .filter { it.clockIn.isBefore(end) }
            .map { it.toModel() }

        // Which days carry an approved correction: one query, bounded by this
        // month's fichajes, returning ids only. One per day would be an N+1; an
        // unbounded one read every approved correction in the company's history
        // for each summary, which is what this used to do (D-011).
        val monthShiftIds = shifts.map { it.id }
        val correctedIds = if (monthShiftIds.isEmpty()) {
            emptySet()
        } else {
            correctionRepository
                .findShiftIdsWithStatus(monthShiftIds, CorrectionStatus.APPROVED)
                .toSet()
        }

        return MonthlySummaryCalculator.calculate(
            employeeId = requestedEmployeeId,
            year = year,
            month = month,
            contractType = employee.contractType,
            shifts = shifts,
            correctedShifts = correctedIds
        )
    }

    private fun loadShiftInProgress(shiftId: UUID, employeeId: UUID): ShiftEntity {
        val shift = shiftRepository.findById(shiftId)
            .orElseThrow { ShiftNotFoundException(shiftId) }

        // Ownership is checked against the authenticated subject, so a caller
        // cannot operate on someone else's shift by passing its id.
        if (shift.employee.id != employeeId) {
            throw ForbiddenException(
                "El fichaje no pertenece al usuario autenticado"
            )
        }

        if (shift.status != ShiftStatus.IN_PROGRESS) throw ShiftNotInProgressException()

        return shift
    }

    private fun ShiftLocationInput.toEmbeddable() = ShiftLocationEmbeddable(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters
    )
}
