package com.granatum.core

import com.granatum.core.api.mappers.toDto
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.model.ShiftValues
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.ContractType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.service.ShiftCorrectionService
import com.granatum.core.service.ShiftService
import com.granatum.core.service.ShiftLocationInput
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * SC-011: the REPRESENTATIVE role reads the whole workforce's register, sees no
 * locations, and writes nothing.
 *
 * The role exists because article 34.9 names worker representatives as
 * recipients of the register. Its scope is the narrowest that satisfies that,
 * and this test is what keeps it narrow.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class RepresentativeReadOnlyIT {

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
    @Autowired lateinit var employeeRepository: EmployeeRepository

    private val madrid = com.granatum.core.api.util.CivilDateRange.ZONE
    private val representative = UUID.randomUUID()

    private fun madrid(date: String, time: String) =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(madrid).toInstant()

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

    private val seville = ShiftLocationInput(
        latitude = BigDecimal("37.389100"),
        longitude = BigDecimal("-5.984500"),
        accuracyMeters = 12
    )

    /** With a location recorded at both ends, so there is something to hide. */
    private fun shiftWithLocation(employee: EmployeeEntity) = run {
        val f = shiftService.clockIn(
            employee.id, madrid("2026-10-05", "07:00"), seville
        )
        shiftService.clockOut(
            f.id, employee.id, madrid("2026-10-05", "16:00"), seville
        )
    }

    @Test
    fun `a REPRESENTATIVE reads any person's register`() {
        val employee = newEmployee()
        shiftWithLocation(employee)

        val result = shiftService.findByEmployeeAndRange(
            requestedEmployeeId = employee.id,
            requesterId = representative,
            role = Role.REPRESENTATIVE,
            from = LocalDate.parse("2026-10-01"),
            to = LocalDate.parse("2026-10-31")
        )

        assertEquals(1, result.size)
        assertEquals(540, result.single().workedMinutes)
    }

    /**
     * FR-023b. The fields are **dropped**, not nulled - and the difference
     * matters: null already means "recorded without a location", so reusing it
     * here would make the two cases indistinguishable and misrepresent the
     * register.
     *
     * Asserted against the model too, to show the data really is there and is
     * being withheld at the presentation boundary rather than never recorded.
     */
    @Test
    fun `a REPRESENTATIVE never sees a location, while a manager does`() {
        val employee = newEmployee()
        val shift = shiftWithLocation(employee)

        // The location is in the register.
        assertNotNull(shift.clockInLocation)
        assertNotNull(shift.clockOutLocation)

        val forManager = shift.toDto(includeLocation = true)
        assertNotNull(forManager.clockInLocation, "a manager is shown the location")
        assertNotNull(forManager.clockOutLocation)

        val forRepresentative = shift.toDto(includeLocation = false)
        assertNull(
            forRepresentative.clockInLocation,
            "article 34.9 entitles representatives to the register, not to where " +
                "each person was: showing it would process personal data with no " +
                "obligation behind it"
        )
        assertNull(forRepresentative.clockOutLocation)

        // Everything else is intact: this is omission of one field, not a
        // degraded view of the record.
        assertEquals(forManager.clockIn, forRepresentative.clockIn)
        assertEquals(forManager.clockOut, forRepresentative.clockOut)
        assertEquals(forManager.workedMinutes, forRepresentative.workedMinutes)
        assertEquals(forManager.breaks, forRepresentative.breaks)
    }

    /** FR-023c: every write path refuses, not just some. */
    @Test
    fun `a REPRESENTATIVE cannot write anything`() {
        val employee = newEmployee()
        val shift = shiftWithLocation(employee)

        // Cannot request a correction.
        assertFailsWith<ForbiddenException> {
            correctionService.request(
                shift.id, representative, Role.REPRESENTATIVE, "No deberia poder",
                ShiftValues(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "15:00"), emptyList())
            )
        }

        // Nor resolve one filed by someone else.
        val correctionRequest = correctionService.request(
            shift.id, employee.id, Role.EMPLOYEE, "Correccion legitima",
            ShiftValues(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "15:00"), emptyList())
        )
        assertFailsWith<ForbiddenException> {
            correctionService.approve(correctionRequest.id, representative, Role.REPRESENTATIVE)
        }
        assertFailsWith<ForbiddenException> {
            correctionService.reject(
                correctionRequest.id, representative, Role.REPRESENTATIVE, "No deberia poder rechazar"
            )
        }
    }

    /**
     * A REPRESENTATIVE has no fichajes of their own to record: they are not staff
     * for this purpose. Clocking in requires an existing empleado row, and there
     * is none for this subject, so the attempt fails rather than silently
     * creating a shift for a non-employee.
     */
    @Test
    fun `a REPRESENTATIVE has no shift to clock`() {
        assertFailsWith<com.granatum.core.domain.exception.EmployeeNotFoundException> {
            shiftService.clockIn(representative, madrid("2026-10-05", "07:00"), null)
        }
    }
}
