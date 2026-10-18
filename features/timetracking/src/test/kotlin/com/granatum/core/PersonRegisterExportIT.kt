package com.granatum.core

import com.granatum.core.api.util.CivilDateRange
import com.granatum.core.domain.exception.EmployeeNotFoundException
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.InconsistentValuesException
import com.granatum.core.domain.model.ExportTarget
import com.granatum.core.domain.service.RegisterCsvWriter
import com.granatum.core.domain.model.ShiftValues
import com.granatum.core.domain.model.BreakValues
import com.granatum.core.domain.type.ShiftStatus
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.BreakType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.infrastructure.database.repositories.ShiftRepository
import com.granatum.core.service.ShiftCorrectionService
import com.granatum.core.service.RegisterExportService
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
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * US1: a person downloads their own register, and every row says what the
 * register says (FR-001 to FR-006, FR-013, FR-016, FR-018; SC-001, SC-002,
 * SC-010).
 *
 * One person is seeded once with every kind of day the file has to get right,
 * and each test reads the same export from its own angle.
 */
@SpringBootTest(classes = [TimetrackingTestApplication::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersonRegisterExportIT {

    companion object {
        // Started here and not by @Testcontainers: with PER_CLASS, Spring builds
        // the context while creating the test instance, before that extension's
        // beforeAll would start the container.
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
    @Autowired lateinit var corrections: ShiftCorrectionService
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var shiftRepository: ShiftRepository
    @Autowired lateinit var dataSource: DataSource

    private val seed by lazy { RegisterSeed(dataSource, employeeRepository) }

    private lateinit var ana: EmployeeEntity
    private lateinit var luis: EmployeeEntity
    private lateinit var marta: EmployeeEntity

    private val from = LocalDate.parse("2025-03-01")
    private val to = LocalDate.parse("2025-10-31")

    private fun values(clockIn: String, clockOut: String, vararg breaks: Triple<BreakType, String, String>) =
        ShiftValues(
            seed.instantOf(clockIn),
            seed.instantOf(clockOut),
            breaks.map { (t, i, f) -> BreakValues(t, seed.instantOf(i), seed.instantOf(f)) }
        )

    @BeforeAll
    fun seedData() {
        ana = seed.employee(name = "Ana", lastName = "Pérez", document = "12345678Z")
        luis = seed.employee(name = "Luis", lastName = "Gil")
        // Registered before feature 012: the whole name in `name`, no last name.
        marta = seed.employee(name = "Marta Ruiz")
        val otherPerson = seed.employee(name = "Otra Persona")

        // Outside the range on both sides, and someone else's day inside it.
        seed.shift(ana, "2025-02-28 07:00", "2025-02-28 15:00")
        seed.shift(ana, "2025-11-01 07:00", "2025-11-01 15:00")
        seed.shift(otherPerson, "2025-03-03 07:00", "2025-03-03 15:00")

        // A plain day with a break, with a PENDING correction that must not show.
        val normal = seed.shift(
            ana, "2025-03-03 07:00", "2025-03-03 16:00",
            listOf(RegisterSeed.SeedBreak(BreakType.MEAL, "2025-03-03 11:00", "2025-03-03 11:30"))
        )
        corrections.request(normal, ana.id, Role.EMPLOYEE, "Pendiente, no aplicada", values("2025-03-03 06:00", "2025-03-03 16:00"))

        // A split day: two shifts, two rows. The first carries a REJECTED correction.
        val tomorrow = seed.shift(ana, "2025-03-04 07:00", "2025-03-04 11:00")
        seed.shift(ana, "2025-03-04 15:00", "2025-03-04 19:00")
        val rejected = corrections.request(tomorrow, ana.id, Role.EMPLOYEE, "Sera rechazada", values("2025-03-04 06:00", "2025-03-04 11:00"))
        corrections.reject(rejected.id, luis.id, Role.MANAGER, "No procede")

        // Corrected twice: the originals are those of the FIRST approval.
        val corrected = seed.shift(ana, "2025-03-05 07:00", "2025-03-05 16:00")
        val firstResponse = corrections.request(
            corrected, ana.id, Role.EMPLOYEE, "Olvide la pausa",
            values("2025-03-05 07:00", "2025-03-05 16:00", Triple(BreakType.MEAL, "2025-03-05 11:00", "2025-03-05 11:45"))
        )
        corrections.approve(firstResponse.id, luis.id, Role.MANAGER)
        val secondResponse = corrections.request(
            corrected, ana.id, Role.EMPLOYEE, "Sali mas tarde",
            values("2025-03-05 07:00", "2025-03-05 16:30", Triple(BreakType.MEAL, "2025-03-05 11:00", "2025-03-05 11:45"))
        )
        corrections.approve(secondResponse.id, marta.id, Role.MANAGER)

        // INCOMPLETE and never fixed; and INCOMPLETE later reconstructed.
        seed.shift(ana, "2025-03-06 08:00", null, status = ShiftStatus.INCOMPLETE, wasIncomplete = true)
        val reconstructed = seed.shift(ana, "2025-03-07 08:00", null, status = ShiftStatus.INCOMPLETE, wasIncomplete = true)
        val reconstruction = corrections.request(reconstructed, ana.id, Role.EMPLOYEE, "Olvide fichar la salida", values("2025-03-07 08:00", "2025-03-07 16:00"))
        corrections.approve(reconstruction.id, luis.id, Role.MANAGER)

        // Across midnight; and across the change to winter time (03:00 -> 02:00).
        seed.shift(ana, "2025-05-09 22:00", "2025-05-10 06:00")
        seed.shift(ana, "2025-10-25 22:00", "2025-10-26 07:00")

        // Still open.
        seed.shift(ana, "2025-10-30 07:00", null)
    }

    private fun export(
        employeeId: UUID = ana.id,
        requester: UUID = ana.id,
        role: Role = Role.EMPLOYEE,
        d: LocalDate = from,
        h: LocalDate = to
    ): ByteArray {
        val out = ByteArrayOutputStream()
        exportService.export(ExportTarget.Person(employeeId), d, h, requester, role, out)
        return out.toByteArray()
    }

    private fun rows() = RegisterCsvReader.rows(export())

    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(CivilDateRange.ZONE)
    private val time = DateTimeFormatter.ofPattern("HH:mm").withZone(CivilDateRange.ZONE)

    @Test
    fun `one row per shift in the range, in order, and only this person's`() {
        val rows = rows()
        assertEquals(9, rows.size, "nine shifts of Ana inside the range")
        assertTrue(rows.all { it["Nombre"] == "Ana" && it["Apellidos"] == "Pérez" })
        assertEquals(
            listOf("2025-03-03", "2025-03-04", "2025-03-04", "2025-03-05", "2025-03-06", "2025-03-07", "2025-05-09", "2025-10-25", "2025-10-30"),
            rows.map { it["Fecha"] }
        )
    }

    /** SC-002: every row crossed with the shift as it stands in the database. */
    @Test
    fun `every row matches the shift in force`() {
        val currentShifts = shiftRepository
            .findAllByEmployeeIdAndClockInBetweenOrderByClockInDesc(ana.id, CivilDateRange.startOfDay(from), CivilDateRange.startOfDay(to.plusDays(1)))
            .sortedBy { it.clockIn }
        val rows = rows()
        assertEquals(currentShifts.size, rows.size)

        currentShifts.zip(rows).forEach { (f, row) ->
            assertEquals(dateTime.format(f.clockIn), row["Entrada"])
            assertEquals(f.clockOut?.let(dateTime::format).orEmpty(), row["Salida"])
            assertEquals(RegisterCsvWriter.label(f.status), row["Estado"])
            assertEquals(
                if (f.status == ShiftStatus.CLOSED) f.workedMinutes.toString() else "",
                row["Minutos trabajados"]
            )
            assertEquals(
                f.breaks.sortedBy { it.startedAt }.joinToString(", ") { "${RegisterCsvWriter.label(it.type)} ${time.format(it.startedAt)}-${it.endedAt?.let(time::format).orEmpty()}" },
                row["Pausas"]
            )
            assertEquals(if (f.wasIncomplete) "Sí" else "No", row["Completado a posteriori"])
            assertEquals("12345678Z", row["Documento"])
        }
    }

    @Test
    fun `a break shows its kind, start and end`() {
        val row = rows().first()
        assertEquals("COMIDA 11:00-11:30", row["Pausas"])
        assertEquals("8:30", row["Horas trabajadas"])
        assertEquals("510", row["Minutos trabajados"])
        assertEquals("No", row["Corregido"], "a pending correction is not applied")
    }

    @Test
    fun `a split day gives two rows and a rejected correction does not mark it`() {
        val split = rows().filter { it["Fecha"] == "2025-03-04" }
        assertEquals(listOf("2025-03-04 07:00", "2025-03-04 15:00"), split.map { it["Entrada"] })
        assertTrue(split.all { it["Corregido"] == "No" && it["Correcciones"] == "" })
    }

    /** FR-004, FR-005: values in force, originals of the first approval, every approval named. */
    @Test
    fun `a corrected shift shows the values in force, the first originals and who corrected it`() {
        val row = rows().single { it["Fecha"] == "2025-03-05" }
        assertEquals("2025-03-05 16:30", row["Salida"], "the latest approved values")
        assertEquals("COMIDA 11:00-11:45", row["Pausas"])
        assertEquals("Sí", row["Corregido"])
        assertEquals("2025-03-05 07:00", row["Entrada original"])
        assertEquals("2025-03-05 16:00", row["Salida original"], "what was clocked, not what the first correction left")
        assertEquals("", row["Pausas originales"])

        val parts = row["Correcciones"]!!.split(" | ")
        assertEquals(2, parts.size, "approved corrections only, oldest first")
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2} solicitada por Ana Pérez, aprobada por Luis Gil""").matches(parts[0]), parts[0])
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2} solicitada por Ana Pérez, aprobada por Marta Ruiz""").matches(parts[1]), parts[1])
    }

    /** FR-006 and SC-010. */
    @Test
    fun `open and incomplete shifts leave hours empty, and a reconstructed one says so`() {
        val rows = rows()
        listOf("2025-03-06", "2025-10-30").forEach { date ->
            val row = rows.single { it["Fecha"] == date }
            assertEquals("", row["Salida"])
            assertEquals("", row["Horas trabajadas"], "never a zero that claims nothing was worked")
            assertEquals("", row["Minutos trabajados"])
        }
        assertEquals("INCOMPLETO", rows.single { it["Fecha"] == "2025-03-06" }["Estado"])
        assertEquals("EN_CURSO", rows.single { it["Fecha"] == "2025-10-30" }["Estado"])

        val reconstructed = rows.single { it["Fecha"] == "2025-03-07" }
        assertEquals("CERRADO", reconstructed["Estado"])
        assertEquals("Sí", reconstructed["Completado a posteriori"])
        assertEquals("Sí", reconstructed["Corregido"])
        assertEquals("2025-03-07 08:00", reconstructed["Entrada original"])
        assertEquals(
            "",
            reconstructed["Salida original"],
            "the original had no exit; the stored snapshot fills it with the entry, which must not be shown as a time"
        )
    }

    @Test
    fun `a shift across midnight belongs to the day it started and shows its exit with the date`() {
        val row = rows().single { it["Entrada"] == "2025-05-09 22:00" }
        assertEquals("2025-05-09", row["Fecha"])
        assertEquals("2025-05-10 06:00", row["Salida"])
        assertEquals("480", row["Minutos trabajados"])
    }

    /**
     * 22:00 to 07:00 across the change to winter time is ten real hours: the
     * clock goes back at 03:00. Shown in local time at both ends, computed on
     * instants (FR-003).
     */
    @Test
    fun `a shift across the change to winter time shows local times and counts real minutes`() {
        val row = rows().single { it["Fecha"] == "2025-10-25" }
        assertEquals("2025-10-25 22:00", row["Entrada"])
        assertEquals("2025-10-26 07:00", row["Salida"])
        assertEquals("600", row["Minutos trabajados"])
        assertEquals("10:00", row["Horas trabajadas"])
    }

    @Test
    fun `a range with no shifts gives only the header`() {
        val bytes = export(d = LocalDate.parse("2024-01-01"), h = LocalDate.parse("2024-01-31"))
        assertEquals(1, RegisterCsvReader.records(bytes).size)
        assertEquals(listOf("Nombre", "Apellidos"), RegisterCsvReader.header(bytes).take(2))
    }

    @Test
    fun `an inverted range is rejected`() {
        assertFailsWith<InconsistentValuesException> { export(d = to, h = from) }
    }

    /** FR-013: an EMPLOYEE is bound to the token subject, whatever id they send. */
    @Test
    fun `an EMPLOYEE cannot export someone else`() {
        assertFailsWith<ForbiddenException> { export(employeeId = luis.id, requester = ana.id) }
    }

    @Test
    fun `a manager can export a person`() {
        assertEquals(9, RegisterCsvReader.rows(export(requester = luis.id, role = Role.MANAGER)).size)
    }

    @Test
    fun `an unknown person is not found`() {
        assertFailsWith<EmployeeNotFoundException> {
            export(employeeId = UUID.randomUUID(), requester = luis.id, role = Role.ADMIN)
        }
    }

    /** FR-018: reading the register writes nothing to it. */
    @Test
    fun `exporting changes nothing in the register`() {
        val before = seed.registerFingerprint()
        export()
        export(requester = luis.id, role = Role.ADMIN)
        assertEquals(before, seed.registerFingerprint())
    }
}
