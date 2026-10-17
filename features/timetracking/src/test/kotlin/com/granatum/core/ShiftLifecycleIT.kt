package com.granatum.core

import com.granatum.core.domain.exception.EmployeeInactiveException
import com.granatum.core.domain.exception.ShiftAlreadyInProgressException
import com.granatum.core.domain.exception.BreakOpenOnCloseException
import com.granatum.core.domain.exception.BreakAlreadyOpenException
import com.granatum.core.domain.type.ShiftStatus
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
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Exercises the whole working-day lifecycle against a real Postgres, running the
 * real Flyway migrations.
 *
 * Testcontainers rather than an in-memory database on purpose: H2 does not
 * reproduce partial unique indexes, RLS, or TIMESTAMPTZ semantics, so it would
 * be testing something else. The partial index is checked here precisely
 * because it is the guarantee a service-side check cannot give.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class ShiftLifecycleIT {

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
        }
    }

    @Autowired lateinit var shiftService: ShiftService
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var dataSource: DataSource

    private val madrid = com.granatum.core.api.util.CivilDateRange.ZONE

    private fun madrid(date: String, time: String) =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(madrid).toInstant()

    private fun newEmployee(active: Boolean = true): EmployeeEntity =
        employeeRepository.save(
            EmployeeEntity(
                name = "Persona de prueba",
                // Distinct per run so the unique index does not collide across tests.
                identityDocument = "T${UUID.randomUUID().toString().take(8).uppercase()}",
                position = "Florista",
                contractType = ContractType.FULL_TIME,
                hireDate = LocalDate.parse("2026-10-01"),
                active = active
            )
        )

    /** SC-001: the criterion the whole feature exists to satisfy. */
    @Test
    fun `a full day with two breaks yields the worked minutes`() {
        val employee = newEmployee()

        var shift = shiftService.clockIn(
            employeeId = employee.id,
            occurredAt = madrid("2026-10-05", "07:00"),
            location = null
        )
        assertEquals(ShiftStatus.IN_PROGRESS, shift.status)
        assertNull(shift.workedMinutes, "worked minutes are only known at closing time")

        shift = shiftService.startBreak(
            shift.id, employee.id, madrid("2026-10-05", "09:00"), BreakType.REST
        )
        shift = shiftService.endBreak(
            shift.id, employee.id, madrid("2026-10-05", "09:15")
        )
        shift = shiftService.startBreak(
            shift.id, employee.id, madrid("2026-10-05", "11:00"), BreakType.MEAL
        )
        shift = shiftService.endBreak(
            shift.id, employee.id, madrid("2026-10-05", "11:45")
        )

        shift = shiftService.clockOut(
            shift.id, employee.id, madrid("2026-10-05", "16:00"), null
        )

        assertEquals(ShiftStatus.CLOSED, shift.status)
        assertEquals(480, shift.workedMinutes, "9h shift minus 1h of breaks")
        assertEquals(2, shift.breaks.size)
        assertEquals(false, shift.wasIncomplete)
    }

    @Test
    fun `a day without breaks counts the whole interval`() {
        val employee = newEmployee()
        var shift = shiftService.clockIn(
            employee.id, madrid("2026-10-05", "07:00"), null
        )
        shift = shiftService.clockOut(
            shift.id, employee.id, madrid("2026-10-05", "15:00"), null
        )
        assertEquals(480, shift.workedMinutes)
    }

    /** SC-006 against real TIMESTAMPTZ arithmetic, not just the pure calculator. */
    @Test
    fun `a day across the autumn clock change is stored and computed as nine hours`() {
        val employee = newEmployee()
        var shift = shiftService.clockIn(
            employee.id, madrid("2026-10-25", "00:00"), null
        )
        shift = shiftService.clockOut(
            shift.id, employee.id, madrid("2026-10-25", "08:00"), null
        )
        assertEquals(
            540,
            shift.workedMinutes,
            "the night the clocks go back has 25 hours; 480 here would mean the " +
                "instant was not preserved through the database"
        )
    }

    @Test
    fun `a day crossing midnight is a single shift`() {
        val employee = newEmployee()
        var shift = shiftService.clockIn(
            employee.id, madrid("2026-10-07", "22:00"), null
        )
        shift = shiftService.clockOut(
            shift.id, employee.id, madrid("2026-10-08", "06:00"), null
        )
        assertEquals(480, shift.workedMinutes)
    }

    @Test
    fun `a second clock-in is rejected while one is open`() {
        val employee = newEmployee()
        shiftService.clockIn(employee.id, madrid("2026-10-05", "07:00"), null)

        assertFailsWith<ShiftAlreadyInProgressException> {
            shiftService.clockIn(employee.id, madrid("2026-10-05", "07:05"), null)
        }
    }

    @Test
    fun `an inactive employee cannot clock in`() {
        val employee = newEmployee(active = false)

        assertFailsWith<EmployeeInactiveException> {
            shiftService.clockIn(employee.id, madrid("2026-10-05", "07:00"), null)
        }
    }

    @Test
    fun `a second break is rejected while one is open`() {
        val employee = newEmployee()
        val shift = shiftService.clockIn(
            employee.id, madrid("2026-10-05", "07:00"), null
        )
        shiftService.startBreak(
            shift.id, employee.id, madrid("2026-10-05", "09:00"), BreakType.REST
        )

        assertFailsWith<BreakAlreadyOpenException> {
            shiftService.startBreak(
                shift.id, employee.id, madrid("2026-10-05", "09:30"), BreakType.MEAL
            )
        }
    }

    @Test
    fun `closing is rejected while a break is still open`() {
        val employee = newEmployee()
        val shift = shiftService.clockIn(
            employee.id, madrid("2026-10-05", "07:00"), null
        )
        shiftService.startBreak(
            shift.id, employee.id, madrid("2026-10-05", "09:00"), BreakType.REST
        )

        assertFailsWith<BreakOpenOnCloseException> {
            shiftService.clockOut(
                shift.id, employee.id, madrid("2026-10-05", "16:00"), null
            )
        }
    }

    /**
     * The partial unique index, not the service check, is what makes FR-002 hold
     * under concurrency: two simultaneous clock-ins both pass the service's read
     * because each reads before either writes.
     *
     * Asserted by writing directly through JDBC, bypassing the service
     * altogether - the point is that the database refuses even when nothing in
     * the application is looking.
     */
    @Test
    fun `the database itself refuses a second open shift for the same employee`() {
        val employee = newEmployee()
        shiftService.clockIn(employee.id, madrid("2026-10-05", "07:00"), null)

        val failure = assertFailsWith<java.sql.SQLException> {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO shifts
                            (id, employee_id, clock_in, status, was_incomplete,
                             created_at, updated_at)
                        VALUES ('${UUID.randomUUID()}', '${employee.id}', now(),
                                'IN_PROGRESS', FALSE, now(), now())
                        """.trimIndent()
                    )
                }
            }
        }

        assertEquals(
            true,
            failure.message!!.contains("uk_shifts_employee_in_progress"),
            "the rejection must come from the partial unique index: ${failure.message}"
        )
    }
}
