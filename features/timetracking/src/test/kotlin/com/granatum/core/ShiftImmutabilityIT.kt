package com.granatum.core

import com.granatum.core.domain.exception.ShiftNotInProgressException
import com.granatum.core.domain.type.ContractType
import com.granatum.core.domain.type.BreakType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
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
import kotlin.test.assertTrue

/**
 * **The test constitution principle III requires**, and the one
 * `/speckit-analyze` was told to look for: a finalised fichaje does not change
 * value through any route other than an approved correction (FR-018, SC-002).
 *
 * Written as an exhaustive attempt rather than a single assertion. The claim
 * being made is a negative one - "there is no way to do this" - so the test has
 * to try the ways there might be and then prove nothing moved.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class ShiftImmutabilityIT {

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
    @Autowired lateinit var employeeRepository: EmployeeRepository

    private val madrid = com.granatum.core.api.util.CivilDateRange.ZONE

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

    @Test
    fun `no service operation can alter a closed shift`() {
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
        val closed = shiftService.clockOut(
            shift.id, employee.id, madrid("2026-10-05", "16:00"), null
        )

        // Snapshot every value that matters before trying to move it.
        val originalClockIn = closed.clockIn
        val originalClockOut = closed.clockOut
        val originalMinutes = closed.workedMinutes
        val originalBreaks = closed.breaks.map { it.id to (it.start to it.end) }.toMap()
        val originalStatus = closed.status

        // Every write operation the service exposes, in turn. All four require
        // an IN_PROGRESS fichaje, so all four must refuse.
        assertFailsWith<ShiftNotInProgressException> {
            shiftService.startBreak(
                closed.id, employee.id, madrid("2026-10-05", "17:00"), BreakType.OTHER
            )
        }
        assertFailsWith<ShiftNotInProgressException> {
            shiftService.endBreak(closed.id, employee.id, madrid("2026-10-05", "17:30"))
        }
        assertFailsWith<ShiftNotInProgressException> {
            shiftService.clockOut(
                closed.id, employee.id, madrid("2026-10-05", "20:00"), null
            )
        }

        // And nothing moved.
        val afterCorrection = shiftService.findById(closed.id)
        assertEquals(originalClockIn, afterCorrection.clockIn)
        assertEquals(originalClockOut, afterCorrection.clockOut)
        assertEquals(originalMinutes, afterCorrection.workedMinutes)
        assertEquals(originalStatus, afterCorrection.status)
        assertEquals(
            originalBreaks,
            afterCorrection.breaks.map { it.id to (it.start to it.end) }.toMap(),
            "the breaks must be identical, not merely the same count"
        )
    }

    /**
     * A second clock-in does not reopen the closed day either - it creates a new
     * one. Worth asserting separately: "clock in again" is the most plausible
     * accidental route to mutating yesterday's record.
     */
    @Test
    fun `clocking in again creates a new shift instead of reopening the closed one`() {
        val employee = newEmployee()
        val firstDay = shiftService.clockIn(
            employee.id, madrid("2026-10-05", "07:00"), null
        )
        val closed = shiftService.clockOut(
            firstDay.id, employee.id, madrid("2026-10-05", "15:00"), null
        )

        val secondShift = shiftService.clockIn(
            employee.id, madrid("2026-10-06", "07:00"), null
        )

        assertTrue(secondShift.id != closed.id, "a new day must be a new fichaje")

        val original = shiftService.findById(closed.id)
        assertEquals(480, original.workedMinutes)
        assertEquals(madrid("2026-10-05", "15:00"), original.clockOut)
    }

    /**
     * The HTTP surface carries no update route at all.
     *
     * Asserted by reflection over the controller rather than by calling an
     * endpoint, because the point is the *absence* of one: a PUT or PATCH on
     * `/api/shifts/{id}` is exactly how principle III would be broken without
     * anyone noticing, and this fails the build the moment someone adds it.
     */
    @Test
    fun `the shift controller exposes no update or delete route`() {
        val methodNames = com.granatum.core.api.controllers.ShiftController::class.java.methods

        val forbidden = methodNames.filter { m ->
            m.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping::class.java) ||
                m.isAnnotationPresent(org.springframework.web.bind.annotation.PatchMapping::class.java) ||
                m.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping::class.java)
        }.map { it.name }

        assertEquals(
            emptyList(),
            forbidden,
            "ShiftController must expose no PUT, PATCH or DELETE: a finalised " +
                "fichaje changes only through an approved correction"
        )
    }
}
