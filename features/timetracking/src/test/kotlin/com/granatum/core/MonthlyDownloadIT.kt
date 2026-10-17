package com.granatum.core

import com.granatum.core.api.util.CivilDateRange
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.InconsistentValuesException
import com.granatum.core.domain.model.ShiftValues
import com.granatum.core.domain.type.ExportScope
import com.granatum.core.domain.type.ShiftStatus
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.ContractType
import com.granatum.core.domain.type.BreakType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.infrastructure.database.repositories.RegisterExportRepository
import com.granatum.core.service.ShiftCorrectionService
import com.granatum.core.service.RegisterExportService
import com.granatum.core.service.ShiftService
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * US2: the monthly download (FR-019 to FR-023, SC-003).
 *
 * Its total must be **exactly** the on-screen summary's, always (FR-020). The
 * month seeded here holds every case where two separate calculations would
 * drift: a corrected day, a reconstructed one, an unresolved one, a shift that
 * started the evening before the month, and one that starts at exactly 00:00
 * on the first of the next month - a round time a correction can easily set,
 * and the boundary an inclusive "between" gets wrong.
 */
@SpringBootTest(classes = [TimetrackingTestApplication::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonthlyDownloadIT {

    companion object {
        // Started here: with PER_CLASS the context is built before the
        // @Testcontainers extension would start it (see PersonRegisterExportIT).
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

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

    @Autowired lateinit var exportService: RegisterExportService
    @Autowired lateinit var shiftService: ShiftService
    @Autowired lateinit var corrections: ShiftCorrectionService
    @Autowired lateinit var exports: RegisterExportRepository
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var dataSource: DataSource

    private val seed by lazy { RegisterSeed(dataSource, employeeRepository) }

    private lateinit var person: EmployeeEntity
    private lateinit var manager: EmployeeEntity

    @BeforeAll
    fun seedData() {
        person = seed.employee(name = "Paula Parcial", type = ContractType.PART_TIME)
        manager = seed.employee(name = "Encargado")

        // Belongs to February: it started there, even though it ends in March.
        seed.shift(person, "2025-02-28 22:00", "2025-03-01 02:00")

        seed.shift(
            person, "2025-03-03 09:00", "2025-03-03 14:00",
            listOf(RegisterSeed.SeedBreak(BreakType.REST, "2025-03-03 11:00", "2025-03-03 11:15"))
        )

        val corrected = seed.shift(person, "2025-03-04 09:00", "2025-03-04 14:00")
        val c = corrections.request(
            corrected, person.id, Role.EMPLOYEE, "Sali mas tarde",
            ShiftValues(seed.instantOf("2025-03-04 09:00"), seed.instantOf("2025-03-04 15:30"), emptyList())
        )
        corrections.approve(c.id, manager.id, Role.MANAGER)

        val reconstructed = seed.shift(person, "2025-03-05 09:00", null, status = ShiftStatus.INCOMPLETE, wasIncomplete = true)
        val r = corrections.request(
            reconstructed, person.id, Role.EMPLOYEE, "Olvide fichar la salida",
            ShiftValues(seed.instantOf("2025-03-05 09:00"), seed.instantOf("2025-03-05 13:00"), emptyList())
        )
        corrections.approve(r.id, manager.id, Role.MANAGER)

        // Still unresolved: contributes nothing, in both.
        seed.shift(person, "2025-03-06 09:00", null, status = ShiftStatus.INCOMPLETE, wasIncomplete = true)

        // Exactly midnight on April 1st: April's, not March's.
        seed.shift(person, "2025-04-01 00:00", "2025-04-01 04:00")
    }

    private fun download(
        year: Int = 2025,
        month: Int = 3,
        requester: UUID = person.id,
        role: Role = Role.EMPLOYEE,
        employeeId: UUID = person.id
    ): ByteArray {
        val out = ByteArrayOutputStream()
        exportService.downloadMonthly(employeeId, year, month, requester, role, out)
        return out.toByteArray()
    }

    /** The cell under [column] in the block row that starts with [label]. */
    private fun chunk(bytes: ByteArray, label: String, column: String? = null): String {
        val records = RegisterCsvReader.records(bytes)
        val row = records.single { it.first() == label }
        return if (column == null) row[1] else row[records.first().indexOf(column)]
    }

    @Test
    fun `every shift of the calendar month, and only those`() {
        val rows = RegisterCsvReader.rows(download())
        assertEquals(listOf("2025-03-03", "2025-03-04", "2025-03-05", "2025-03-06"), rows.map { it["Fecha"] })
    }

    /** FR-020, SC-003: the same number as the on-screen summary, from the same function. */
    @Test
    fun `the monthly total is exactly the on-screen summary's`() {
        val summary = shiftService.monthlySummary(person.id, person.id, Role.EMPLOYEE, 2025, 3)
        val bytes = download()

        assertEquals(summary.totalWorkedMinutes.toString(), chunk(bytes, "Total del mes", "Minutos trabajados"))
        assertEquals(285 + 390 + 240, summary.totalWorkedMinutes, "and that number is the right one")
        assertEquals("15:15", chunk(bytes, "Total del mes", "Horas trabajadas"))
        assertEquals(
            RegisterCsvReader.rows(bytes).sumOf { it["Minutos trabajados"]!!.ifEmpty { "0" }.toInt() },
            summary.totalWorkedMinutes,
            "the total is the sum of the rows shown above it"
        )
    }

    @Test
    fun `the corrected and the reconstructed day are flagged`() {
        val rows = RegisterCsvReader.rows(download())
        assertEquals("Sí", rows.single { it["Fecha"] == "2025-03-04" }["Corregido"])
        val reconstructed = rows.single { it["Fecha"] == "2025-03-05" }
        assertEquals("Sí", reconstructed["Completado a posteriori"])
        assertEquals("Sí", reconstructed["Corregido"])
    }

    /** FR-021: art. 12.4.c obliges this summary for part-time contracts. */
    @Test
    fun `the contract type is stated`() {
        assertEquals("PARCIAL", chunk(download(), "Tipo de contrato"))
    }

    /** FR-022 */
    @Test
    fun `a past month is closed and the current one is not`() {
        assertEquals("Sí", chunk(download(), "Mes cerrado"))

        val today = LocalDate.now(CivilDateRange.ZONE)
        assertEquals("No", chunk(download(year = today.year, month = today.monthValue), "Mes cerrado"))
    }

    /** FR-023 */
    @Test
    fun `the person, MANAGER, ADMIN and REPRESENTATIVE may download it, another EMPLOYEE may not`() {
        listOf(Role.MANAGER, Role.ADMIN, Role.REPRESENTATIVE).forEach { role ->
            assertEquals(4, RegisterCsvReader.rows(download(requester = UUID.randomUUID(), role = role)).size, "$role")
        }
        assertFailsWith<ForbiddenException> { download(requester = manager.id, role = Role.EMPLOYEE) }
    }

    @Test
    fun `a month outside 1 to 12 is rejected`() {
        assertFailsWith<InconsistentValuesException> { download(month = 13) }
        assertFailsWith<InconsistentValuesException> { download(month = 0) }
    }

    @Test
    fun `it is recorded as MONTHLY covering the whole month`() {
        val otherPerson = seed.employee()
        val out = ByteArrayOutputStream()
        exportService.downloadMonthly(otherPerson.id, 2025, 2, otherPerson.id, Role.EMPLOYEE, out)

        val logEntry = exports.search(otherPerson.id, null, null, null, null).single()
        assertEquals(ExportScope.MONTHLY, logEntry.scope)
        assertEquals(LocalDate.parse("2025-02-01"), logEntry.from)
        assertEquals(LocalDate.parse("2025-02-28"), logEntry.to)
        assertTrue(logEntry.completed)
        assertEquals(0, logEntry.rowCount)
    }
}
