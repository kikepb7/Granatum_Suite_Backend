package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.ContractType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.entities.ShiftCorrectionRequestEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.service.ShiftService
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D-011: the monthly summary reads the corrections of **its** month, and nothing
 * else.
 *
 * The previous version loaded every approved correction in the table - every
 * person, every month, the whole history - on each summary, although its
 * comment said "one query over the month". The query count was indeed one, which
 * is why nothing noticed: the cost was in what that one query brought back, and
 * it grew with the history of the company. The monthly download of the whole
 * staff (feature 003) would have repeated it once per person.
 *
 * So this counts **entities loaded**, not only statements. A test that counted
 * statements alone passed against the broken version.
 *
 * Rows are inserted through JDBC with fixed past dates, for the reason
 * [RetentionIT] gives: the service refuses timestamps outside the clock
 * tolerance, and the month under test must not depend on today's date.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class MonthlySummaryQueriesIT {

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
            registry.add("spring.jpa.properties.hibernate.generate_statistics") { "true" }
        }
    }

    @Autowired lateinit var shiftService: ShiftService
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var entityManagerFactory: EntityManagerFactory

    private val statistics: Statistics
        get() = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    private fun newEmployee(): EmployeeEntity =
        employeeRepository.save(
            EmployeeEntity(
                name = "Persona de prueba",
                identityDocument = "T${UUID.randomUUID().toString().take(8).uppercase()}",
                position = "Florista",
                contractType = ContractType.FULL_TIME,
                hireDate = LocalDate.parse("2020-01-01"),
                active = true
            )
        )

    /** A closed eight-hour shift on [date], with an approved correction on it. */
    private fun correctedShift(employee: EmployeeEntity, date: String): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.createStatement().use { s ->
                val clockIn = "TIMESTAMPTZ '$date 08:00:00 Europe/Madrid'"
                s.execute(
                    """
                    INSERT INTO shifts
                        (id, employee_id, clock_in, clock_out, status, worked_minutes,
                         was_incomplete, created_at, updated_at)
                    VALUES ('$id', '${employee.id}', $clockIn,
                            $clockIn + interval '8 hours', 'CLOSED', 480, FALSE,
                            now(), now())
                    """.trimIndent()
                )
                s.execute(
                    """
                    INSERT INTO shift_correction_requests
                        (id, shift_id, requester_id, reason, proposed_values,
                         original_values, status, resolved_by_id, resolved_at, created_at)
                    VALUES ('${UUID.randomUUID()}', '$id', '${employee.id}',
                            'Motivo de prueba', '{}'::jsonb, '{}'::jsonb, 'APPROVED',
                            '${UUID.randomUUID()}', now(), now())
                    """.trimIndent()
                )
            }
        }
        return id
    }

    private fun summary(employee: EmployeeEntity) =
        shiftService.monthlySummary(employee.id, employee.id, Role.EMPLOYEE, 2025, 3)

    @Test
    fun `the summary loads only the corrections of its own month`() {
        val queried = newEmployee()
        correctedShift(queried, "2025-03-10")

        statistics.clear()
        val before = summary(queried)
        val statementsWithoutForeign = statistics.prepareStatementCount

        // Fifty approved corrections the summary has no business reading: other
        // people in the same month, and the same person in other months.
        repeat(25) { correctedShift(newEmployee(), "2025-03-${10 + it % 15}") }
        repeat(25) { correctedShift(queried, "2024-${(1 + it % 12).toString().padStart(2, '0')}-15") }

        statistics.clear()
        val after = summary(queried)
        val loaded = statistics
            .getEntityStatistics(ShiftCorrectionRequestEntity::class.java.name)
            .loadCount

        assertTrue(after.days.single().corrected, "the day of the month is still flagged")
        assertEquals(before, after, "foreign corrections must not change the result")
        assertTrue(
            loaded <= 1,
            "the summary loaded $loaded corrections for a month that has one; " +
                "it must not read the rest of the company's history"
        )
        assertEquals(
            statementsWithoutForeign,
            statistics.prepareStatementCount,
            "the number of statements must not grow with corrections outside the month"
        )
    }
}
