package com.granatum.core

import com.granatum.core.domain.exception.DuplicateDocumentException
import com.granatum.core.domain.exception.EmployeeInactiveException
import com.granatum.core.domain.type.ShiftStatus
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.ContractType
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.service.EmployeeService
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
 * Staff management against a real Postgres: engagement, exit, and the history
 * surviving both.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class EmployeeIT {

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
            registry.add("timetracking.retention.cron") { "0 0 4 1 1 *" }
        }
    }

    @Autowired lateinit var employeeService: EmployeeService
    @Autowired lateinit var shiftService: ShiftService
    @Autowired lateinit var employeeRepository: EmployeeRepository

    private val madrid = com.granatum.core.api.util.CivilDateRange.ZONE

    private fun madrid(date: String, time: String) =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(madrid).toInstant()

    /** A distinct valid DNI per call, check letter computed, not invented. */
    private fun validDni(): String {
        val number = (10_000_000..99_999_999).random()
        val letter = "TRWAGMYFPDXBNJZSQVHLCKE"[number % 23]
        return "$number$letter"
    }

    @Test
    fun `an engagement is registered as active`() {
        val employee = employeeService.create(
            name = "Maria Lopez Ruiz",
            identityDocument = validDni(),
            position = "Florista",
            contractType = ContractType.FULL_TIME,
            hireDate = LocalDate.parse("2026-10-01")
        )

        assertTrue(employee.active)
        assertEquals("Maria Lopez Ruiz", employee.name)
        assertEquals(ContractType.FULL_TIME, employee.contractType)
    }

    /** FR-029, through the unique index and the service check alike. */
    @Test
    fun `a duplicate document is rejected`() {
        val dni = validDni()
        employeeService.create(
            "Primera Persona", dni, "Florista",
            ContractType.FULL_TIME, LocalDate.parse("2026-10-01")
        )

        assertFailsWith<DuplicateDocumentException> {
            employeeService.create(
                "Segunda Persona", dni, "Montador",
                ContractType.PART_TIME, LocalDate.parse("2026-10-02")
            )
        }
    }

    /**
     * The punctuation case: the same document written differently must still
     * collide. Without normalisation the unique index would accept it and the
     * same person would exist twice.
     */
    @Test
    fun `the same document with different punctuation still collides`() {
        val number = (10_000_000..99_999_999).random()
        val letter = "TRWAGMYFPDXBNJZSQVHLCKE"[number % 23]

        employeeService.create(
            "Primera Persona", "$number$letter", "Florista",
            ContractType.FULL_TIME, LocalDate.parse("2026-10-01")
        )

        assertFailsWith<DuplicateDocumentException> {
            employeeService.create(
                "Segunda Persona", "$number-${letter.lowercaseChar()}", "Montador",
                ContractType.PART_TIME, LocalDate.parse("2026-10-02")
            )
        }
    }

    @Test
    fun `the stored document is normalised`() {
        val number = (10_000_000..99_999_999).random()
        val letter = "TRWAGMYFPDXBNJZSQVHLCKE"[number % 23]

        val employee = employeeService.create(
            "Persona", " $number-${letter.lowercaseChar()} ", "Florista",
            ContractType.FULL_TIME, LocalDate.parse("2026-10-01")
        )

        assertEquals("$number$letter", employee.identityDocument)
    }

    /** FR-030: the exit is logical, and the history survives it intact. */
    @Test
    fun `deactivating stops clocking in and leaves the history whole`() {
        val employee = employeeService.create(
            "Persona", validDni(), "Florista",
            ContractType.FULL_TIME, LocalDate.parse("2026-10-01")
        )

        val f = shiftService.clockIn(employee.id, madrid("2026-10-05", "07:00"), null)
        shiftService.clockOut(f.id, employee.id, madrid("2026-10-05", "16:00"), null)

        employeeService.setActive(employee.id, false)

        assertFailsWith<EmployeeInactiveException> {
            shiftService.clockIn(employee.id, madrid("2026-10-06", "07:00"), null)
        }

        val history = shiftService.findByEmployeeAndRange(
            employee.id, employee.id, Role.ADMIN,
            LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")
        )
        assertEquals(1, history.size, "the history must survive the exit")
        assertEquals(540, history.single().workedMinutes)
    }

    @Test
    fun `reactivating allows clocking in again`() {
        val employee = employeeService.create(
            "Persona", validDni(), "Florista",
            ContractType.FULL_TIME, LocalDate.parse("2026-10-01")
        )

        employeeService.setActive(employee.id, false)
        employeeService.setActive(employee.id, true)

        val f = shiftService.clockIn(employee.id, madrid("2026-10-06", "07:00"), null)
        assertEquals(ShiftStatus.IN_PROGRESS, f.status)
    }

    /**
     * FR-030, asserted where it cannot be argued with: the repository offers no
     * deletion at all, so there is nothing to remember not to call.
     */
    @Test
    fun `the employee repository exposes no deletion`() {
        val methodNames = EmployeeRepository::class.java.methods.map { it.name }

        assertEquals(
            emptyList(),
            methodNames.filter { it.startsWith("delete") || it.startsWith("remove") },
            "removing a person would destroy four years of working-time history"
        )
    }

    @Test
    fun `an update changes the job data but not the document`() {
        val dni = validDni()
        val employee = employeeService.create(
            "Nombre Original", dni, "Florista",
            ContractType.FULL_TIME, LocalDate.parse("2026-10-01")
        )

        val updated = employeeService.update(
            id = employee.id,
            name = "Nombre Corregido",
            position = "Encargada de taller",
            contractType = ContractType.PART_TIME,
            hireDate = LocalDate.parse("2026-09-15")
        )

        assertEquals("Nombre Corregido", updated.name)
        assertEquals("Encargada de taller", updated.position)
        assertEquals(ContractType.PART_TIME, updated.contractType)
        assertEquals(dni, updated.identityDocument, "the document is untouched by an update")
        assertTrue(updated.active, "nor is the active flag, which has its own endpoint")
    }

    @Test
    fun `listing can be filtered by active status`() {
        val activeEmployee = employeeService.create(
            "Activa", validDni(), "Florista",
            ContractType.FULL_TIME, LocalDate.parse("2026-10-01")
        )
        val inactiveEmployee = employeeService.create(
            "Inactiva", validDni(), "Montador",
            ContractType.HOURLY, LocalDate.parse("2026-10-01")
        )
        employeeService.setActive(inactiveEmployee.id, false)

        val activeIds = employeeService.findAll(active = true).map { it.id }
        val inactiveIds = employeeService.findAll(active = false).map { it.id }

        assertTrue(activeEmployee.id in activeIds)
        assertTrue(inactiveEmployee.id in inactiveIds)
        assertTrue(inactiveEmployee.id !in activeIds)
    }

    /**
     * T088, the gap the brief left open: deactivating someone who still has an
     * open shift is allowed, and the daily job will mark it INCOMPLETE. Blocking
     * the exit would hold up an administrative action because a person forgot to
     * clock out - making one person's mistake somebody else's problem.
     */
    @Test
    fun `someone with an open shift can still be deactivated`() {
        val employee = employeeService.create(
            "Persona", validDni(), "Florista",
            ContractType.FULL_TIME, LocalDate.parse("2026-10-01")
        )
        val openShift = shiftService.clockIn(
            employee.id, madrid("2026-10-05", "07:00"), null
        )

        val inactive = employeeService.setActive(employee.id, false)

        assertEquals(false, inactive.active)
        assertEquals(
            ShiftStatus.IN_PROGRESS,
            shiftService.findById(openShift.id).status,
            "the shift is left for the daily job to mark INCOMPLETE, not closed by guesswork"
        )
    }
}
