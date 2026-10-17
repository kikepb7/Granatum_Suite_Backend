package com.granatum.core

import com.granatum.core.domain.exception.EmployeeInactiveException
import com.granatum.core.domain.exception.EmployeeNotFoundException
import com.granatum.core.domain.exception.ShiftNotInProgressException
import com.granatum.core.domain.exception.ShiftAlreadyInProgressException
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.BreakOpenOnCloseException
import com.granatum.core.domain.exception.BreakNotOpenException
import com.granatum.core.domain.exception.BreakAlreadyOpenException
import com.granatum.core.domain.type.ShiftStatus
import com.granatum.core.domain.type.ContractType
import com.granatum.core.domain.type.BreakType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.entities.ShiftEntity
import com.granatum.core.infrastructure.database.entities.ShiftBreakEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.infrastructure.database.repositories.ShiftRepository
import com.granatum.core.infrastructure.database.repositories.ShiftBreakRepository
import com.granatum.core.infrastructure.database.repositories.ShiftCorrectionRequestRepository
import com.granatum.core.service.ShiftService
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests of the service's branching, with MockK and no database.
 *
 * These rules are also exercised by `ShiftLifecycleIT` against a real
 * Postgres, which is the stronger check. They are worth having separately for
 * what the integration test cannot give: they run in milliseconds, they isolate
 * the decision from the schema, and a failure here points at the branch rather
 * than at a stack of framework frames. Principle V asks for both levels, and
 * this is the level that fails fast.
 */
class ShiftServiceTest {

    private val shiftRepository = mockk<ShiftRepository>()
    private val shiftBreakRepository = mockk<ShiftBreakRepository>(relaxed = true)
    private val employeeRepository = mockk<EmployeeRepository>()
    private val correctionRepository = mockk<ShiftCorrectionRequestRepository>()

    private val service = ShiftService(
        shiftRepository, shiftBreakRepository, employeeRepository, correctionRepository
    )

    private val employeeId = UUID.randomUUID()
    private val now: Instant = Instant.parse("2026-10-05T07:00:00Z")

    private fun employee(active: Boolean = true) = EmployeeEntity(
        id = employeeId,
        name = "Persona",
        identityDocument = "12345678Z",
        position = "Florista",
        contractType = ContractType.FULL_TIME,
        hireDate = LocalDate.parse("2026-10-01"),
        active = active
    )

    private fun otherEmployee(id: UUID) = EmployeeEntity(
        id = id,
        name = "Otra persona",
        identityDocument = "00000000T",
        position = "Montador",
        contractType = ContractType.PART_TIME,
        hireDate = LocalDate.parse("2026-10-01"),
        active = true
    )

    private fun shift(
        status: ShiftStatus = ShiftStatus.IN_PROGRESS,
        shiftOwnerId: UUID = employeeId,
        breaks: List<ShiftBreakEntity> = emptyList()
    ): ShiftEntity = ShiftEntity(
        employee = if (shiftOwnerId == employeeId) employee() else otherEmployee(shiftOwnerId),
        clockIn = now,
        status = status
    ).also { f -> breaks.forEach { f.addBreak(it) } }

    // --- FR-002 ------------------------------------------------------------

    @Test
    fun `clocking in is rejected when a shift is already open`() {
        every { employeeRepository.findById(employeeId) } returns Optional.of(employee())
        every {
            shiftRepository.findByEmployeeIdAndStatus(employeeId, ShiftStatus.IN_PROGRESS)
        } returns shift()

        assertFailsWith<ShiftAlreadyInProgressException> {
            service.clockIn(employeeId, now, null)
        }
    }

    // --- FR-010 ------------------------------------------------------------

    @Test
    fun `clocking in is rejected for an inactive employee`() {
        every { employeeRepository.findById(employeeId) } returns
            Optional.of(employee(active = false))

        assertFailsWith<EmployeeInactiveException> {
            service.clockIn(employeeId, now, null)
        }
    }

    @Test
    fun `clocking in is rejected for an unknown employee`() {
        every { employeeRepository.findById(employeeId) } returns Optional.empty()

        assertFailsWith<EmployeeNotFoundException> {
            service.clockIn(employeeId, now, null)
        }
    }

    // --- FR-004 ------------------------------------------------------------

    @Test
    fun `starting a break is rejected when one is already open`() {
        val openBreakEntity = ShiftBreakEntity(type = BreakType.REST, startedAt = now)
        val f = shift(breaks = listOf(openBreakEntity))
        every { shiftRepository.findById(f.id) } returns Optional.of(f)

        assertFailsWith<BreakAlreadyOpenException> {
            service.startBreak(f.id, employeeId, now.plusSeconds(60), BreakType.MEAL)
        }
    }

    @Test
    fun `ending a break is rejected when none is open`() {
        val f = shift()
        every { shiftRepository.findById(f.id) } returns Optional.of(f)

        assertFailsWith<BreakNotOpenException> {
            service.endBreak(f.id, employeeId, now.plusSeconds(60))
        }
    }

    // --- FR-006 ------------------------------------------------------------

    @Test
    fun `closing is rejected while a break is open`() {
        val openBreakEntity = ShiftBreakEntity(type = BreakType.REST, startedAt = now.plusSeconds(60))
        val f = shift(breaks = listOf(openBreakEntity))
        every { shiftRepository.findById(f.id) } returns Optional.of(f)

        assertFailsWith<BreakOpenOnCloseException> {
            service.clockOut(f.id, employeeId, now.plusSeconds(3600), null)
        }
    }

    // --- The state guard ---------------------------------------------------

    @Test
    fun `operations on a closed shift are rejected`() {
        val closed = shift(status = ShiftStatus.CLOSED)
        every { shiftRepository.findById(closed.id) } returns Optional.of(closed)

        assertFailsWith<ShiftNotInProgressException> {
            service.startBreak(closed.id, employeeId, now, BreakType.MEAL)
        }
        assertFailsWith<ShiftNotInProgressException> {
            service.endBreak(closed.id, employeeId, now)
        }
        assertFailsWith<ShiftNotInProgressException> {
            service.clockOut(closed.id, employeeId, now, null)
        }
    }

    /**
     * Ownership is checked against the authenticated subject, so passing
     * someone else's fichaje id does not grant access to it.
     */
    @Test
    fun `operating on another person's shift is rejected`() {
        val foreign = shift(shiftOwnerId = UUID.randomUUID())
        every { shiftRepository.findById(foreign.id) } returns Optional.of(foreign)

        assertFailsWith<ForbiddenException> {
            service.startBreak(foreign.id, employeeId, now, BreakType.MEAL)
        }
    }
}
