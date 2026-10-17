package com.granatum.core

import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.InconsistentValuesException
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.ContractType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.infrastructure.database.repositories.ShiftRepository
import com.granatum.core.infrastructure.database.repositories.ShiftBreakRepository
import com.granatum.core.infrastructure.database.repositories.ShiftCorrectionRequestRepository
import com.granatum.core.service.ShiftService
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The visibility boundary, isolated from the database (FR-022, FR-023, SC-004).
 *
 * The case that matters is an EMPLOYEE naming someone else's id explicitly:
 * trusting the path id and ignoring the token is the single mistake that would
 * turn this into a data breach, so it is asserted here as well as in the
 * integration test.
 */
class ShiftAuthorizationTest {

    private val shiftRepository = mockk<ShiftRepository>(relaxed = true)
    private val shiftBreakRepository = mockk<ShiftBreakRepository>(relaxed = true)
    private val employeeRepository = mockk<EmployeeRepository>()
    private val correctionRepository = mockk<ShiftCorrectionRequestRepository>(relaxed = true)

    private val service = ShiftService(
        shiftRepository, shiftBreakRepository, employeeRepository, correctionRepository
    )

    private val me = UUID.randomUUID()
    private val another = UUID.randomUUID()
    private val from = LocalDate.parse("2026-10-01")
    private val to = LocalDate.parse("2026-10-31")

    private fun employee(id: UUID) = EmployeeEntity(
        id = id,
        name = "Persona",
        identityDocument = "12345678Z",
        position = "Florista",
        contractType = ContractType.FULL_TIME,
        hireDate = from,
        active = true
    )

    @Test
    fun `an EMPLOYEE is refused another person's register even naming their id`() {
        assertFailsWith<ForbiddenException> {
            service.findByEmployeeAndRange(another, me, Role.EMPLOYEE, from, to)
        }
    }

    @Test
    fun `an EMPLOYEE may read their own`() {
        every { employeeRepository.findById(me) } returns Optional.of(employee(me))
        every {
            shiftRepository.findAllByEmployeeIdAndClockInBetweenOrderByClockInDesc(me, any(), any())
        } returns emptyList()

        service.findByEmployeeAndRange(me, me, Role.EMPLOYEE, from, to)
    }

    @Test
    fun `MANAGER, ADMIN and REPRESENTATIVE may read anyone's`() {
        every { employeeRepository.findById(another) } returns Optional.of(employee(another))
        every {
            shiftRepository.findAllByEmployeeIdAndClockInBetweenOrderByClockInDesc(another, any(), any())
        } returns emptyList()

        listOf(Role.MANAGER, Role.ADMIN, Role.REPRESENTATIVE).forEach { role ->
            service.findByEmployeeAndRange(another, me, role, from, to)
        }
    }

    @Test
    fun `an EMPLOYEE cannot list the whole workforce`() {
        assertFailsWith<ForbiddenException> {
            service.findAllInRange(Role.EMPLOYEE, from, to)
        }
    }

    /**
     * The authorization check runs **before** the range check, so a caller
     * cannot learn whether another person exists by sending a malformed range.
     */
    @Test
    fun `authorization is checked before the range`() {
        assertFailsWith<ForbiddenException> {
            service.findByEmployeeAndRange(another, me, Role.EMPLOYEE, to, from)
        }
    }

    @Test
    fun `an inverted range is rejected for a caller who is allowed`() {
        assertFailsWith<InconsistentValuesException> {
            service.findByEmployeeAndRange(me, me, Role.EMPLOYEE, to, from)
        }
    }
}
