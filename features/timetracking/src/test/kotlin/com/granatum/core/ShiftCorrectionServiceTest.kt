package com.granatum.core

import com.granatum.core.domain.exception.ShiftNotFinishedException
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.CorrectionRequestAlreadyResolvedException
import com.granatum.core.domain.exception.InconsistentValuesException
import com.granatum.core.domain.model.ShiftValues
import com.granatum.core.domain.model.BreakValues
import com.granatum.core.domain.type.ShiftStatus
import com.granatum.core.domain.type.CorrectionStatus
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.ContractType
import com.granatum.core.domain.type.BreakType
import com.granatum.core.infrastructure.database.ShiftValuesJson
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.entities.ShiftEntity
import com.granatum.core.infrastructure.database.entities.ShiftCorrectionRequestEntity
import com.granatum.core.infrastructure.database.repositories.ShiftRepository
import com.granatum.core.infrastructure.database.repositories.ShiftCorrectionRequestRepository
import com.granatum.core.service.ShiftCorrectionService
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The correction service's refusal branches, without a database.
 *
 * `ShiftCorrectionFlowIT` covers the same rules against a real Postgres, which is
 * the stronger check. These run in milliseconds and point at the branch rather
 * than at a stack of framework frames, which is what makes them worth keeping
 * alongside it.
 */
class ShiftCorrectionServiceTest {

    private val correctionRepository = mockk<ShiftCorrectionRequestRepository>(relaxed = true)
    private val shiftRepository = mockk<ShiftRepository>(relaxed = true)
    private val json = ShiftValuesJson()

    private val service = ShiftCorrectionService(
        correctionRepository,
        shiftRepository,
        json,
        mockk(relaxed = true),
        Clock.fixed(Instant.parse("2026-10-06T09:00:00Z"), ZoneOffset.UTC)
    )

    private val employeeId = UUID.randomUUID()
    private val clockIn: Instant = Instant.parse("2026-10-05T07:00:00Z")
    private val clockOut: Instant = Instant.parse("2026-10-05T16:00:00Z")

    private fun employee(id: UUID = employeeId) = EmployeeEntity(
        id = id,
        name = "Persona",
        identityDocument = "12345678Z",
        position = "Florista",
        contractType = ContractType.FULL_TIME,
        hireDate = LocalDate.parse("2026-10-01"),
        active = true
    )

    private fun shift(status: ShiftStatus = ShiftStatus.CLOSED, shiftOwnerId: UUID = employeeId) =
        ShiftEntity(
            employee = employee(shiftOwnerId),
            clockIn = clockIn,
            clockOut = clockOut,
            status = status,
            workedMinutes = 540
        )

    private fun correctionRequest(
        status: CorrectionStatus = CorrectionStatus.PENDING,
        requesterId: UUID = employeeId
    ) = ShiftCorrectionRequestEntity(
        shift = shift(),
        requesterId = requesterId,
        reason = "Motivo suficientemente largo",
        proposedValues = json.write(ShiftValues(clockIn, clockOut, emptyList())),
        status = status
    )

    private val validProposal = ShiftValues(
        clockIn, clockOut,
        listOf(BreakValues(BreakType.MEAL, Instant.parse("2026-10-05T11:00:00Z"), Instant.parse("2026-10-05T11:45:00Z")))
    )

    // --- Requesting ---------------------------------------------------------

    @Test
    fun `an open shift cannot be corrected`() {
        val openShift = shift(status = ShiftStatus.IN_PROGRESS)
        every { shiftRepository.findById(openShift.id) } returns Optional.of(openShift)

        assertFailsWith<ShiftNotFinishedException> {
            service.request(openShift.id, employeeId, Role.EMPLOYEE, "Motivo largo", validProposal)
        }
    }

    @Test
    fun `an EMPLOYEE cannot correct someone else's shift`() {
        val foreign = shift(shiftOwnerId = UUID.randomUUID())
        every { shiftRepository.findById(foreign.id) } returns Optional.of(foreign)

        assertFailsWith<ForbiddenException> {
            service.request(foreign.id, employeeId, Role.EMPLOYEE, "Motivo largo", validProposal)
        }
    }

    @Test
    fun `a REPRESENTATIVE cannot request a correction`() {
        val f = shift()
        every { shiftRepository.findById(f.id) } returns Optional.of(f)

        assertFailsWith<ForbiddenException> {
            service.request(f.id, UUID.randomUUID(), Role.REPRESENTATIVE, "Motivo largo", validProposal)
        }
    }

    @Test
    fun `an incoherent proposal is rejected at request time`() {
        val f = shift()
        every { shiftRepository.findById(f.id) } returns Optional.of(f)

        assertFailsWith<InconsistentValuesException> {
            service.request(
                f.id, employeeId, Role.EMPLOYEE, "Salida antes de la entrada",
                ShiftValues(clockOut, clockIn, emptyList())
            )
        }

        assertFailsWith<InconsistentValuesException> {
            service.request(
                f.id, employeeId, Role.EMPLOYEE, "Pausas solapadas",
                ShiftValues(
                    clockIn, clockOut,
                    listOf(
                        BreakValues(BreakType.MEAL, Instant.parse("2026-10-05T09:00:00Z"), Instant.parse("2026-10-05T10:00:00Z")),
                        BreakValues(BreakType.OTHER, Instant.parse("2026-10-05T09:30:00Z"), Instant.parse("2026-10-05T10:30:00Z"))
                    )
                )
            )
        }
    }

    // --- Resolving ----------------------------------------------------------

    @Test
    fun `only MANAGER and ADMIN can resolve`() {
        val s = correctionRequest()
        every { correctionRepository.findById(s.id) } returns Optional.of(s)

        assertFailsWith<ForbiddenException> {
            service.approve(s.id, UUID.randomUUID(), Role.EMPLOYEE)
        }
        assertFailsWith<ForbiddenException> {
            service.approve(s.id, UUID.randomUUID(), Role.REPRESENTATIVE)
        }
    }

    /** FR-015a, the rule the original FR-015 missed. */
    @Test
    fun `nobody resolves their own request, not even an MANAGER`() {
        val own = correctionRequest(requesterId = employeeId)
        every { correctionRepository.findById(own.id) } returns Optional.of(own)

        assertFailsWith<ForbiddenException> {
            service.approve(own.id, employeeId, Role.MANAGER)
        }
    }

    @Test
    fun `an already resolved request cannot be resolved again`() {
        val resolved = correctionRequest(status = CorrectionStatus.APPROVED)
        every { correctionRepository.findById(resolved.id) } returns Optional.of(resolved)

        assertFailsWith<CorrectionRequestAlreadyResolvedException> {
            service.approve(resolved.id, UUID.randomUUID(), Role.MANAGER)
        }
    }

    /**
     * The conditional claim is the real guard, so its zero-rows outcome is
     * asserted directly: a read-then-write cannot hold FR-019 under
     * concurrency, and this is what happens when the other transaction won.
     */
    @Test
    fun `losing the conditional claim is reported as already resolved`() {
        val s = correctionRequest()
        every { correctionRepository.findById(s.id) } returns Optional.of(s)
        every {
            correctionRepository.resolveIfStillPending(any(), any(), any(), any(), any())
        } returns 0

        assertFailsWith<CorrectionRequestAlreadyResolvedException> {
            service.approve(s.id, UUID.randomUUID(), Role.MANAGER)
        }
    }
}
