package com.granatum.core

import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.InconsistentValuesException
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.ContractType
import com.granatum.core.domain.type.BreakType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.service.ShiftService
import org.hibernate.SessionFactory
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
 * Querying the register, and the privacy boundary around it.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class ShiftQueryIT {

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
            // Needed by the N+1 assertion below, which reads Hibernate's own
            // query counter rather than guessing from annotations.
            registry.add("spring.jpa.properties.hibernate.generate_statistics") { "true" }
        }
    }

    @Autowired lateinit var shiftService: ShiftService
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var sessionFactory: SessionFactory

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

    private fun shiftLength(employee: EmployeeEntity, date: String, withBreak: Boolean = false) {
        var f = shiftService.clockIn(employee.id, madrid(date, "07:00"), null)
        if (withBreak) {
            f = shiftService.startBreak(f.id, employee.id, madrid(date, "11:00"), BreakType.MEAL)
            f = shiftService.endBreak(f.id, employee.id, madrid(date, "11:30"))
        }
        shiftService.clockOut(f.id, employee.id, madrid(date, "16:00"), null)
    }

    @Test
    fun `a range returns only the shifts inside it, newest first`() {
        val employee = newEmployee()
        shiftLength(employee, "2026-10-01")
        shiftLength(employee, "2026-10-05")
        shiftLength(employee, "2026-10-20")

        val result = shiftService.findByEmployeeAndRange(
            employee.id, employee.id, Role.EMPLOYEE,
            LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-10")
        )

        assertEquals(2, result.size)
        assertTrue(
            result[0].clockIn.isAfter(result[1].clockIn),
            "ordered by entry descending"
        )
    }

    /** Both bounds inclusive, interpreted in Europe/Madrid. */
    @Test
    fun `both ends of the range are inclusive`() {
        val employee = newEmployee()
        shiftLength(employee, "2026-10-05")

        val sameDay = shiftService.findByEmployeeAndRange(
            employee.id, employee.id, Role.EMPLOYEE,
            LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-05")
        )

        assertEquals(1, sameDay.size, "a single-day range must include that day")
    }

    /**
     * A shift crossing midnight belongs to the day it started, and must not
     * appear twice.
     */
    @Test
    fun `a shift crossing midnight appears only on the day it started`() {
        val employee = newEmployee()
        val f = shiftService.clockIn(employee.id, madrid("2026-10-07", "22:00"), null)
        shiftService.clockOut(f.id, employee.id, madrid("2026-10-08", "06:00"), null)

        val startDay = shiftService.findByEmployeeAndRange(
            employee.id, employee.id, Role.EMPLOYEE,
            LocalDate.parse("2026-10-07"), LocalDate.parse("2026-10-07")
        )
        val endDay = shiftService.findByEmployeeAndRange(
            employee.id, employee.id, Role.EMPLOYEE,
            LocalDate.parse("2026-10-08"), LocalDate.parse("2026-10-08")
        )

        assertEquals(1, startDay.size)
        assertEquals(0, endDay.size, "it must not be counted twice")
    }

    @Test
    fun `an empty range returns an empty list rather than an error`() {
        val employee = newEmployee()

        val result = shiftService.findByEmployeeAndRange(
            employee.id, employee.id, Role.EMPLOYEE,
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31")
        )

        assertEquals(emptyList(), result)
    }

    @Test
    fun `an inverted range is rejected`() {
        val employee = newEmployee()

        assertFailsWith<InconsistentValuesException> {
            shiftService.findByEmployeeAndRange(
                employee.id, employee.id, Role.EMPLOYEE,
                LocalDate.parse("2026-10-31"), LocalDate.parse("2026-10-01")
            )
        }
    }

    /**
     * SC-004, the privacy boundary: an EMPLOYEE cannot reach another person's
     * register **even when naming their id explicitly**, which is the whole
     * point - the path id is for readability, never authority.
     */
    @Test
    fun `an EMPLOYEE cannot read another person's register even by naming their id`() {
        val me = newEmployee()
        val another = newEmployee()
        shiftLength(another, "2026-10-05")

        assertFailsWith<ForbiddenException> {
            shiftService.findByEmployeeAndRange(
                requestedEmployeeId = another.id,
                requesterId = me.id,
                role = Role.EMPLOYEE,
                from = LocalDate.parse("2026-10-01"),
                to = LocalDate.parse("2026-10-31")
            )
        }
    }

    @Test
    fun `an EMPLOYEE cannot list the whole workforce`() {
        val me = newEmployee()

        assertFailsWith<ForbiddenException> {
            shiftService.findAllInRange(
                Role.EMPLOYEE, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")
            )
        }
    }

    @Test
    fun `MANAGER, ADMIN and REPRESENTATIVE can read anyone's register`() {
        val another = newEmployee()
        shiftLength(another, "2026-10-05")

        listOf(Role.MANAGER, Role.ADMIN, Role.REPRESENTATIVE).forEach { role ->
            val result = shiftService.findByEmployeeAndRange(
                requestedEmployeeId = another.id,
                requesterId = UUID.randomUUID(),
                role = role,
                from = LocalDate.parse("2026-10-01"),
                to = LocalDate.parse("2026-10-31")
            )
            assertEquals(1, result.size, "$role must be able to read it")
        }
    }

    /**
     * The N+1 check, done by **counting the queries Hibernate actually ran**
     * rather than by reading the annotations - which is the only way to know.
     *
     * Ten days each with a break: without the `@EntityGraph` this is 1 + 10
     * selects, and a manager asking for the whole workforce multiplies that by
     * the headcount. The assertion is deliberately loose about the exact number
     * and strict about the shape: the count must not grow with the number of
     * rows.
     */
    @Test
    fun `listing shifts with their breaks does not grow queries with the number of rows`() {
        val few = newEmployee()
        (1..2).forEach { d -> shiftLength(few, "2026-11-%02d".format(d), withBreak = true) }

        val many = newEmployee()
        (1..10).forEach { d -> shiftLength(many, "2026-11-%02d".format(d), withBreak = true) }

        fun queriesWhenListing(employeeId: UUID): Long {
            val stats = sessionFactory.statistics
            stats.clear()
            val result = shiftService.findByEmployeeAndRange(
                employeeId, employeeId, Role.EMPLOYEE,
                LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30")
            )
            // Touch the breaks, so a lazy load would show up in the count.
            result.forEach { it.breaks.size }
            return stats.queryExecutionCount + stats.prepareStatementCount
        }

        val with2 = queriesWhenListing(few.id)
        val with10 = queriesWhenListing(many.id)

        assertTrue(
            with10 <= with2 + 1,
            "queries must not scale with rows: 2 shifts took $with2, 10 took $with10 - " +
                "that difference is an N+1, and the @EntityGraph is missing or ineffective"
        )
    }
}
