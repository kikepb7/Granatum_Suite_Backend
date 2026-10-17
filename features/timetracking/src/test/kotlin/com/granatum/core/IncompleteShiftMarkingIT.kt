package com.granatum.core

import com.granatum.core.domain.type.ShiftStatus
import com.granatum.core.domain.type.ContractType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.infrastructure.database.repositories.ShiftRepository
import com.granatum.core.scheduling.IncompleteShiftMarkingJob
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
import kotlin.test.assertTrue

/**
 * SC-009 and SC-010: a shift left open from a previous day becomes INCOMPLETE,
 * stops blocking new shifts, and stays distinguishable afterwards.
 *
 * The job is invoked directly rather than waiting for its cron, which is the
 * only practical way to test a once-a-day schedule.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class IncompleteShiftMarkingIT {

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
            // Never fires on its own during the test: the job is called directly.
            registry.add("timetracking.incomplete.cron") { "0 0 4 1 1 *" }
        }
    }

    @Autowired lateinit var job: IncompleteShiftMarkingJob
    @Autowired lateinit var shiftService: ShiftService
    @Autowired lateinit var shiftRepository: ShiftRepository
    @Autowired lateinit var employeeRepository: EmployeeRepository

    private val madrid = com.granatum.core.api.util.CivilDateRange.ZONE

    private fun yesterdayAt(time: String) =
        LocalDateTime.of(LocalDate.now(madrid).minusDays(1), LocalTime.parse(time))
            .atZone(madrid).toInstant()

    private fun todayAt(time: String) =
        LocalDateTime.of(LocalDate.now(madrid), LocalTime.parse(time))
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

    @Test
    fun `a shift left open yesterday becomes INCOMPLETE and stops blocking new ones`() {
        val employee = newEmployee()
        val forgotten = shiftService.clockIn(employee.id, yesterdayAt("08:00"), null)

        job.markIncomplete()

        val reloaded = shiftRepository.findById(forgotten.id).orElseThrow()
        assertEquals(ShiftStatus.INCOMPLETE, reloaded.status)
        assertTrue(reloaded.wasIncomplete, "the flag must record that it was ever incomplete")

        // FR-012a: the person can clock in today. Locking them out would turn
        // one forgotten clock-out into a second, larger gap in the register.
        val today = shiftService.clockIn(employee.id, todayAt("08:00"), null)
        assertEquals(ShiftStatus.IN_PROGRESS, today.status)
    }

    @Test
    fun `a shift opened today is left alone`() {
        val employee = newEmployee()
        val todayShift = shiftService.clockIn(employee.id, todayAt("08:00"), null)

        job.markIncomplete()

        val reloaded = shiftRepository.findById(todayShift.id).orElseThrow()
        assertEquals(
            ShiftStatus.IN_PROGRESS,
            reloaded.status,
            "today's shift is still in progress; marking it would be wrong, not merely early"
        )
        assertEquals(false, reloaded.wasIncomplete)
    }

    /**
     * Running twice must change nothing the first run did not already do. That
     * is what makes the job safe on several application instances at once
     * without any locking.
     */
    @Test
    fun `running the job twice is idempotent`() {
        val employee = newEmployee()
        val forgotten = shiftService.clockIn(employee.id, yesterdayAt("08:00"), null)

        job.markIncomplete()
        val after1 = shiftRepository.findById(forgotten.id).orElseThrow()
        val statusAfter1 = after1.status
        val flagAfter1 = after1.wasIncomplete

        job.markIncomplete()
        val after2 = shiftRepository.findById(forgotten.id).orElseThrow()

        assertEquals(statusAfter1, after2.status)
        assertEquals(flagAfter1, after2.wasIncomplete)
    }
}
