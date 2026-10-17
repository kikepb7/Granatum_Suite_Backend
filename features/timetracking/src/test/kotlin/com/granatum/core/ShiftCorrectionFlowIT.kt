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
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.infrastructure.database.repositories.ShiftRepository
import com.granatum.core.scheduling.IncompleteShiftMarkingJob
import com.granatum.core.service.ShiftCorrectionService
import com.granatum.core.service.ShiftService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The correction flow: request, approve, reject - and above all that the
 * original survives.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class ShiftCorrectionFlowIT {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("timetracking.incomplete.cron") { "0 0 4 1 1 *" }
        }
    }

    @Autowired lateinit var shiftService: ShiftService
    @Autowired lateinit var correctionService: ShiftCorrectionService
    @Autowired lateinit var shiftRepository: ShiftRepository
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var job: IncompleteShiftMarkingJob
    @Autowired lateinit var correctionController: com.granatum.core.api.controllers.ShiftCorrectionController

    private val madrid = com.granatum.core.api.util.CivilDateRange.ZONE
    private val manager = UUID.randomUUID()

    private fun madrid(date: String, time: String) =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(madrid).toInstant()

    private fun yesterday(time: String) =
        LocalDateTime.of(LocalDate.now(madrid).minusDays(1), LocalTime.parse(time))
            .atZone(madrid).toInstant()

    private fun newEmployee(): EmployeeEntity =
        employeeRepository.save(
            EmployeeEntity(
                name = "Persona de prueba",
                identityDocument = "T${UUID.randomUUID().toString().take(8).uppercase()}",
                position = "Florista",
                contractType = ContractType.FULL_TIME,
                hireDate = LocalDate.parse("2026-10-01"),
                active = true
            )
        )

    private fun closedShift(employee: EmployeeEntity) = run {
        val f = shiftService.clockIn(employee.id, madrid("2026-10-05", "07:00"), null)
        shiftService.clockOut(f.id, employee.id, madrid("2026-10-05", "16:00"), null)
    }

    /** SC-003: the value changes, and the original is still there with its authorship. */
    @Test
    fun `an approved correction applies the new values and preserves the originals`() {
        val employee = newEmployee()
        val shift = closedShift(employee)
        assertEquals(540, shift.workedMinutes)

        val correctionRequest = correctionService.request(
            shiftId = shift.id,
            requesterId = employee.id,
            role = Role.EMPLOYEE,
            reason = "Olvide fichar la pausa de comida",
            proposed = ShiftValues(
                clockIn = madrid("2026-10-05", "07:00"),
                clockOut = madrid("2026-10-05", "16:00"),
                breaks = listOf(
                    BreakValues(BreakType.MEAL, madrid("2026-10-05", "11:00"), madrid("2026-10-05", "11:45"))
                )
            )
        )
        assertEquals(CorrectionStatus.PENDING, correctionRequest.status)

        val approvedRequest = correctionService.approve(correctionRequest.id, manager, Role.MANAGER)

        assertEquals(CorrectionStatus.APPROVED, approvedRequest.status)
        assertEquals(manager, approvedRequest.resolvedById)
        assertNotNull(approvedRequest.resolvedAt)

        // The original is recoverable: 9h with no breaks.
        val originals = assertNotNull(approvedRequest.originalValues)
        assertEquals(emptyList(), originals.breaks)
        assertEquals(madrid("2026-10-05", "16:00"), originals.clockOut)

        // And the fichaje now reflects the correction: 9h minus 45 min.
        //
        // Read through the service rather than the repository: `breaks` is a
        // lazy collection and `open-in-view` is false, so touching it outside a
        // transaction throws. The service maps inside one, which is also the
        // path production uses.
        val reloaded = shiftService.findById(shift.id)
        assertEquals(495, reloaded.workedMinutes)
        assertEquals(1, reloaded.breaks.size)
    }

    @Test
    fun `a rejected correction leaves the shift untouched`() {
        val employee = newEmployee()
        val shift = closedShift(employee)

        val correctionRequest = correctionService.request(
            shift.id, employee.id, Role.EMPLOYEE, "Me marche antes de lo apuntado",
            ShiftValues(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "14:00"), emptyList())
        )

        val rejected = correctionService.reject(
            correctionRequest.id, manager, Role.MANAGER, "No coincide con el parte de obra"
        )

        assertEquals(CorrectionStatus.REJECTED, rejected.status)
        assertNotNull(rejected.resolutionReason)
        assertNull(rejected.originalValues, "nothing was applied, so there is no 'before'")

        val reloaded = shiftRepository.findById(shift.id).orElseThrow()
        assertEquals(540, reloaded.workedMinutes, "the fichaje must be byte-for-byte unchanged")
        assertEquals(madrid("2026-10-05", "16:00"), reloaded.clockOut)
    }

    /** FR-019: both terminal states are final. */
    @Test
    fun `resolving twice is rejected`() {
        val employee = newEmployee()
        val shift = closedShift(employee)
        val correctionRequest = correctionService.request(
            shift.id, employee.id, Role.EMPLOYEE, "Correccion de prueba",
            ShiftValues(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "15:00"), emptyList())
        )

        correctionService.approve(correctionRequest.id, manager, Role.MANAGER)

        assertFailsWith<CorrectionRequestAlreadyResolvedException> {
            correctionService.approve(correctionRequest.id, manager, Role.MANAGER)
        }
        assertFailsWith<CorrectionRequestAlreadyResolvedException> {
            correctionService.reject(correctionRequest.id, manager, Role.MANAGER, "Tarde")
        }
    }

    /**
     * FR-015a, the rule that FR-015 as first written missed.
     *
     * An MANAGER has their own fichajes, so they could file a correction for
     * their own shift and approve it themselves. That is the self-approval the
     * approval step exists to prevent, and the original requirement only barred
     * it for EMPLOYEE - a role that never reaches the resolution path at all.
     */
    @Test
    fun `nobody resolves their own request, not even an MANAGER`() {
        val managerEmployee = newEmployee()
        val shift = closedShift(managerEmployee)

        val correctionRequest = correctionService.request(
            shift.id, managerEmployee.id, Role.MANAGER, "Mi propia correccion",
            ShiftValues(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "15:00"), emptyList())
        )

        assertFailsWith<ForbiddenException> {
            correctionService.approve(correctionRequest.id, managerEmployee.id, Role.MANAGER)
        }
    }

    @Test
    fun `an EMPLOYEE cannot resolve a correction at all`() {
        val employee = newEmployee()
        val shift = closedShift(employee)
        val correctionRequest = correctionService.request(
            shift.id, employee.id, Role.EMPLOYEE, "Correccion de prueba",
            ShiftValues(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "15:00"), emptyList())
        )

        assertFailsWith<ForbiddenException> {
            correctionService.approve(correctionRequest.id, UUID.randomUUID(), Role.EMPLOYEE)
        }
    }

    @Test
    fun `a REPRESENTATIVE cannot request a correction`() {
        val employee = newEmployee()
        val shift = closedShift(employee)

        assertFailsWith<ForbiddenException> {
            correctionService.request(
                shift.id, UUID.randomUUID(), Role.REPRESENTATIVE, "No deberia poder",
                ShiftValues(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "15:00"), emptyList())
            )
        }
    }

    @Test
    fun `an open shift cannot be corrected`() {
        val employee = newEmployee()
        val openShift = shiftService.clockIn(
            employee.id, madrid("2026-10-05", "07:00"), null
        )

        assertFailsWith<ShiftNotFinishedException> {
            correctionService.request(
                openShift.id, employee.id, Role.EMPLOYEE, "Deberia rechazarse",
                ShiftValues(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "15:00"), emptyList())
            )
        }
    }

    @Test
    fun `an incoherent proposal is rejected when requested`() {
        val employee = newEmployee()
        val shift = closedShift(employee)

        assertFailsWith<InconsistentValuesException> {
            correctionService.request(
                shift.id, employee.id, Role.EMPLOYEE, "Salida antes de la entrada",
                ShiftValues(madrid("2026-10-05", "16:00"), madrid("2026-10-05", "07:00"), emptyList())
            )
        }

        assertFailsWith<InconsistentValuesException> {
            correctionService.request(
                shift.id, employee.id, Role.EMPLOYEE, "Pausas solapadas entre si",
                ShiftValues(
                    madrid("2026-10-05", "07:00"), madrid("2026-10-05", "16:00"),
                    listOf(
                        BreakValues(BreakType.MEAL, madrid("2026-10-05", "09:00"), madrid("2026-10-05", "10:00")),
                        BreakValues(BreakType.OTHER, madrid("2026-10-05", "09:30"), madrid("2026-10-05", "10:30"))
                    )
                )
            )
        }
    }

    /** SC-010: a reconstructed day stays distinguishable from one closed at the time. */
    @Test
    fun `completing an INCOMPLETE shift keeps it marked as reconstructed`() {
        val employee = newEmployee()
        val forgotten = shiftService.clockIn(employee.id, yesterday("08:00"), null)

        job.markIncomplete()
        assertEquals(
            ShiftStatus.INCOMPLETE,
            shiftRepository.findById(forgotten.id).orElseThrow().status
        )

        val correctionRequest = correctionService.request(
            forgotten.id, employee.id, Role.EMPLOYEE, "Olvide fichar la salida",
            ShiftValues(yesterday("08:00"), yesterday("16:00"), emptyList())
        )
        correctionService.approve(correctionRequest.id, manager, Role.MANAGER)

        val completedShift = shiftRepository.findById(forgotten.id).orElseThrow()
        assertEquals(ShiftStatus.CLOSED, completedShift.status)
        assertEquals(480, completedShift.workedMinutes)
        assertTrue(
            completedShift.wasIncomplete,
            "the flag must survive completion, or an inspection report could not " +
                "tell a reconstructed day from one closed at the time"
        )
    }

    /**
     * FR-020a. Checked at the controller, because that is where the refusal
     * happens: the DTO declares `location` **only** so its presence can be
     * detected. Leaving the field out looked cleaner and was wrong - Jackson
     * ignores unknown properties, so the request would have been accepted
     * silently and the client would believe it had corrected the location.
     */
    @Test
    fun `a proposal carrying a location is refused rather than ignored`() {
        val employee = newEmployee()
        val shift = closedShift(employee)

        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .authentication = org.springframework.security.authentication
            .UsernamePasswordAuthenticationToken(
                employee.id, null,
                listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_EMPLEADO"))
            )

        try {
            assertFailsWith<com.granatum.core.domain.exception.LocationNotCorrectableException> {
                correctionController.request(
                    shift.id,
                    com.granatum.core.api.dto.CreateShiftCorrectionRequest(
                        reason = "Quiero cambiar donde fiche",
                        proposedValues = com.granatum.core.api.dto.ShiftValuesDto(
                            clockIn = madrid("2026-10-05", "07:00"),
                            clockOut = madrid("2026-10-05", "16:00"),
                            breaks = emptyList(),
                            location = com.granatum.core.api.dto.ShiftLocationDto(
                                latitude = java.math.BigDecimal("37.0"),
                                longitude = java.math.BigDecimal("-5.0")
                            )
                        )
                    )
                )
            }
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext()
        }
    }

    /** A correction that removes a break must actually delete its row. */
    @Test
    fun `a correction can remove a break that was recorded by mistake`() {
        val employee = newEmployee()
        var shift = shiftService.clockIn(
            employee.id, madrid("2026-10-05", "07:00"), null
        )
        shift = shiftService.startBreak(
            shift.id, employee.id, madrid("2026-10-05", "09:00"), BreakType.REST
        )
        shift = shiftService.endBreak(
            shift.id, employee.id, madrid("2026-10-05", "09:30")
        )
        shift = shiftService.clockOut(
            shift.id, employee.id, madrid("2026-10-05", "16:00"), null
        )
        assertEquals(510, shift.workedMinutes)

        val correctionRequest = correctionService.request(
            shift.id, employee.id, Role.EMPLOYEE, "Esa pausa no existio",
            ShiftValues(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "16:00"), emptyList())
        )
        correctionService.approve(correctionRequest.id, manager, Role.MANAGER)

        val reloaded = shiftService.findById(shift.id)
        assertEquals(0, reloaded.breaks.size, "orphanRemoval must delete the row, not orphan it")
        assertEquals(540, reloaded.workedMinutes)
    }
}
