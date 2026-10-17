package com.granatum.core

import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.InconsistentValuesException
import com.granatum.core.domain.model.ShiftValues
import com.granatum.core.domain.model.BreakValues
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.ContractType
import com.granatum.core.domain.type.BreakType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SC-014: the monthly summary adds up, and flags the days that were
 * reconstructed or corrected.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class MonthlySummaryIT {

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
    @Autowired lateinit var job: IncompleteShiftMarkingJob

    private val madrid = com.granatum.core.api.util.CivilDateRange.ZONE
    private val manager = UUID.randomUUID()

    private fun madrid(date: String, time: String) =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(madrid).toInstant()

    private fun newEmployee(type: ContractType = ContractType.FULL_TIME): EmployeeEntity =
        employeeRepository.save(
            EmployeeEntity(
                name = "Persona de prueba",
                identityDocument = "T${UUID.randomUUID().toString().take(8).uppercase()}",
                position = "Florista",
                contractType = type,
                hireDate = LocalDate.parse("2026-10-01"),
                active = true
            )
        )

    private fun shiftLength(employee: EmployeeEntity, date: String, withBreak: Boolean = false) = run {
        var f = shiftService.clockIn(employee.id, madrid(date, "07:00"), null)
        if (withBreak) {
            f = shiftService.startBreak(f.id, employee.id, madrid(date, "11:00"), BreakType.MEAL)
            f = shiftService.endBreak(f.id, employee.id, madrid(date, "11:30"))
        }
        shiftService.clockOut(f.id, employee.id, madrid(date, "16:00"), null)
    }

    @Test
    fun `the monthly total equals the sum of its days`() {
        val employee = newEmployee()
        shiftLength(employee, "2026-10-05")                   // 540
        shiftLength(employee, "2026-10-06", withBreak = true)  // 510
        shiftLength(employee, "2026-10-07")                   // 540

        val summary = shiftService.monthlySummary(
            employee.id, employee.id, Role.EMPLOYEE, 2026, 10
        )

        assertEquals(3, summary.days.size)
        assertEquals(1590, summary.totalWorkedMinutes)
        assertEquals(
            summary.days.sumOf { it.workedMinutes ?: 0 },
            summary.totalWorkedMinutes,
            "the total must be the sum of the days shown, not an independent count"
        )
        assertEquals(30, summary.days.single { it.breakMinutes > 0 }.breakMinutes)
    }

    @Test
    fun `days outside the month are excluded`() {
        val employee = newEmployee()
        shiftLength(employee, "2026-09-30")
        shiftLength(employee, "2026-10-01")
        shiftLength(employee, "2026-11-01")

        val summary = shiftService.monthlySummary(
            employee.id, employee.id, Role.EMPLOYEE, 2026, 10
        )

        assertEquals(1, summary.days.size)
        assertEquals(LocalDate.parse("2026-10-01"), summary.days.single().date)
    }

    /** FR-034: the summary shows the values in force, not the originals. */
    @Test
    fun `a corrected day shows the corrected values and is flagged`() {
        val employee = newEmployee()
        val shift = shiftLength(employee, "2026-10-05")
        assertEquals(540, shift.workedMinutes)

        val correctionRequest = correctionService.request(
            shift.id, employee.id, Role.EMPLOYEE, "Olvide fichar la pausa de comida",
            ShiftValues(
                madrid("2026-10-05", "07:00"), madrid("2026-10-05", "16:00"),
                listOf(BreakValues(BreakType.MEAL, madrid("2026-10-05", "11:00"), madrid("2026-10-05", "11:45")))
            )
        )
        correctionService.approve(correctionRequest.id, manager, Role.MANAGER)

        val summary = shiftService.monthlySummary(
            employee.id, employee.id, Role.EMPLOYEE, 2026, 10
        )
        val day = summary.days.single()

        assertEquals(495, day.workedMinutes, "the corrected value, not the original 540")
        assertEquals(45, day.breakMinutes)
        assertTrue(day.corrected, "the day must be flagged so the reader knows it was changed")
        assertFalse(day.reconstructed, "it was closed at the time; only its values were corrected")
    }

    /** SC-010 carried through to the summary. */
    @Test
    fun `a reconstructed day is flagged as such`() {
        val employee = newEmployee()
        val yesterday = LocalDate.now(madrid).minusDays(1)

        val forgotten = shiftService.clockIn(
            employee.id,
            LocalDateTime.of(yesterday, LocalTime.parse("08:00")).atZone(madrid).toInstant(),
            null
        )
        job.markIncomplete()

        val correctionRequest = correctionService.request(
            forgotten.id, employee.id, Role.EMPLOYEE, "Olvide fichar la salida",
            ShiftValues(
                LocalDateTime.of(yesterday, LocalTime.parse("08:00")).atZone(madrid).toInstant(),
                LocalDateTime.of(yesterday, LocalTime.parse("16:00")).atZone(madrid).toInstant(),
                emptyList()
            )
        )
        correctionService.approve(correctionRequest.id, manager, Role.MANAGER)

        val summary = shiftService.monthlySummary(
            employee.id, employee.id, Role.EMPLOYEE, yesterday.year, yesterday.monthValue
        )
        val day = summary.days.single { it.date == yesterday }

        assertTrue(
            day.reconstructed,
            "an inspection report must be able to say which days were rebuilt"
        )
        assertTrue(day.corrected)
        assertEquals(480, day.workedMinutes)
    }

    /**
     * An open or INCOMPLETE day contributes nothing to the total, and shows a
     * null rather than a zero. A zero would read as "worked nothing", which is a
     * different and false claim.
     */
    @Test
    fun `an unresolved day shows null instead of contributing zero silently`() {
        val employee = newEmployee()
        shiftLength(employee, "2026-10-05")
        shiftService.clockIn(employee.id, madrid("2026-10-06", "07:00"), null)

        val summary = shiftService.monthlySummary(
            employee.id, employee.id, Role.EMPLOYEE, 2026, 10
        )

        assertEquals(2, summary.days.size)
        assertEquals(540, summary.totalWorkedMinutes, "only the closed day counts")
        assertEquals(
            1,
            summary.days.count { it.workedMinutes == null },
            "the open day must be visible as unresolved, not as zero"
        )
    }

    @Test
    fun `the contract type travels with the summary`() {
        val employee = newEmployee(ContractType.PART_TIME)
        shiftLength(employee, "2026-10-05")

        val summary = shiftService.monthlySummary(
            employee.id, employee.id, Role.EMPLOYEE, 2026, 10
        )

        assertEquals(
            ContractType.PART_TIME,
            summary.contractType,
            "article 12.4.c obliges a monthly summary for part-time staff, so the " +
                "consumer has to know whether this is one of those"
        )
    }

    @Test
    fun `an EMPLOYEE cannot read another person's summary`() {
        val me = newEmployee()
        val another = newEmployee()
        shiftLength(another, "2026-10-05")

        assertFailsWith<ForbiddenException> {
            shiftService.monthlySummary(another.id, me.id, Role.EMPLOYEE, 2026, 10)
        }
    }

    @Test
    fun `an invalid month is rejected`() {
        val employee = newEmployee()

        assertFailsWith<InconsistentValuesException> {
            shiftService.monthlySummary(employee.id, employee.id, Role.EMPLOYEE, 2026, 13)
        }
        assertFailsWith<InconsistentValuesException> {
            shiftService.monthlySummary(employee.id, employee.id, Role.EMPLOYEE, 2026, 0)
        }
    }
}
