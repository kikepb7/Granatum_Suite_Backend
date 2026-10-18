package com.granatum.core

import com.granatum.core.domain.contract.ShiftSheetRow
import com.granatum.core.domain.contract.ShiftSheetSource
import com.granatum.core.domain.model.ShiftValues
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.ShiftStatus
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.service.RetentionPeriod
import com.granatum.core.service.ShiftCorrectionService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 012, US2: the shifts of a month as the Google spreadsheet receives
 * them, through the `ShiftSheetSource` contract (research.md D-002).
 *
 * Each test seeds its own month, so they share one database without seeing
 * each other's rows.
 */
@SpringBootTest(classes = [TimetrackingTestApplication::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShiftSheetSourceIT {

    companion object {
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
            // Two shifts per batch: a month of five crosses batch boundaries.
            registry.add("timetracking.export.batch-size") { "2" }
        }
    }

    @Autowired lateinit var source: ShiftSheetSource
    @Autowired lateinit var corrections: ShiftCorrectionService
    @Autowired lateinit var retention: RetentionPeriod
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var dataSource: DataSource

    private val seed by lazy { RegisterSeed(dataSource, employeeRepository) }

    @Test
    fun `rows come ordered by date and clock-in, across people and batches, with name, last name and document`() {
        val ana = seed.employee(name = "Ana", lastName = "García López", document = "11111111H")
        val luis = seed.employee(name = "Luis", lastName = "Pérez", document = "22222222J")
        seed.shift(luis, "2025-01-03 09:00", "2025-01-03 17:00")
        seed.shift(ana, "2025-01-03 07:30", "2025-01-03 15:30")
        seed.shift(ana, "2025-01-02 08:00", "2025-01-02 14:00")
        seed.shift(luis, "2025-01-31 10:00", "2025-01-31 12:15")
        seed.shift(ana, "2025-01-15 07:00", "2025-01-15 15:00")
        seed.shift(ana, "2025-02-01 07:00", "2025-02-01 15:00") // next month
        seed.shift(ana, "2024-12-31 07:00", "2024-12-31 15:00") // previous month

        val month = source.month(YearMonth.of(2025, 1))

        assertEquals(
            listOf(
                ShiftSheetRow("Ana", "García López", "11111111H", LocalDate.parse("2025-01-02"), LocalTime.of(8, 0), LocalTime.of(14, 0)),
                ShiftSheetRow("Ana", "García López", "11111111H", LocalDate.parse("2025-01-03"), LocalTime.of(7, 30), LocalTime.of(15, 30)),
                ShiftSheetRow("Luis", "Pérez", "22222222J", LocalDate.parse("2025-01-03"), LocalTime.of(9, 0), LocalTime.of(17, 0)),
                ShiftSheetRow("Ana", "García López", "11111111H", LocalDate.parse("2025-01-15"), LocalTime.of(7, 0), LocalTime.of(15, 0)),
                ShiftSheetRow("Luis", "Pérez", "22222222J", LocalDate.parse("2025-01-31"), LocalTime.of(10, 0), LocalTime.of(12, 15))
            ),
            month.rows
        )
        assertEquals(0, month.lastNamesPending)
    }

    @Test
    fun `an approved correction shows its current values`() {
        val person = seed.employee(name = "Marta", lastName = "Ruiz")
        val manager = seed.employee(name = "Encargada", lastName = "Sanz")
        val shift = seed.shift(person, "2025-09-04 07:00", "2025-09-04 15:00")
        val request = corrections.request(
            shift, person.id, Role.EMPLOYEE, "Salí más tarde",
            ShiftValues(seed.instantOf("2025-09-04 07:00"), seed.instantOf("2025-09-04 18:00"), emptyList())
        )
        corrections.approve(request.id, manager.id, Role.MANAGER)

        val row = source.month(YearMonth.of(2025, 9)).rows.single()
        assertEquals(LocalTime.of(18, 0), row.clockOut)
    }

    @Test
    fun `an open or incomplete shift has no clock-out`() {
        val person = seed.employee(name = "Pablo", lastName = "Gil")
        seed.shift(person, "2025-03-04 07:00", null)
        seed.shift(person, "2025-03-05 07:00", null, status = ShiftStatus.INCOMPLETE)

        val rows = source.month(YearMonth.of(2025, 3)).rows
        assertEquals(2, rows.size)
        rows.forEach { assertNull(it.clockOut) }
    }

    @Test
    fun `a shift crossing midnight goes in the month and date of its clock-in`() {
        val person = seed.employee(name = "Noa", lastName = "Vidal")
        seed.shift(person, "2025-04-30 22:00", "2025-05-01 06:00")

        val april = source.month(YearMonth.of(2025, 4)).rows
        assertEquals(LocalDate.parse("2025-04-30"), april.single().date)
        assertEquals(LocalTime.of(22, 0), april.single().clockIn)
        assertEquals(LocalTime.of(6, 0), april.single().clockOut)
        assertTrue(source.month(YearMonth.of(2025, 5)).rows.isEmpty())
    }

    @Test
    fun `people who have left are included, and pending last names are counted once per person`() {
        val gone = seed.employee(name = "Antigua Persona", active = false)
        val pending = seed.employee(name = "Otra Persona Antigua")
        val complete = seed.employee(name = "Eva", lastName = "Mora")
        seed.shift(gone, "2025-06-02 07:00", "2025-06-02 15:00")
        seed.shift(gone, "2025-06-03 07:00", "2025-06-03 15:00")
        seed.shift(pending, "2025-06-02 08:00", "2025-06-02 15:00")
        seed.shift(complete, "2025-06-02 09:00", "2025-06-02 15:00")

        val month = source.month(YearMonth.of(2025, 6))

        assertEquals(4, month.rows.size)
        assertEquals(2, month.rows.count { it.name == "Antigua Persona" })
        assertNull(month.rows.first { it.name == "Antigua Persona" }.lastName)
        assertEquals(2, month.lastNamesPending)
    }

    @Test
    fun `a month without shifts has no rows`() {
        val month = source.month(YearMonth.of(2025, 8))
        assertTrue(month.rows.isEmpty())
        assertEquals(0, month.lastNamesPending)
    }

    @Test
    fun `reading writes nothing to the register`() {
        val person = seed.employee(name = "Iris", lastName = "Soler")
        seed.shift(person, "2025-07-01 07:00", "2025-07-01 15:00")
        val before = seed.registerFingerprint()

        source.month(YearMonth.of(2025, 7))

        assertEquals(before, seed.registerFingerprint())
    }

    @Test
    fun `the retention cutoff is the register's`() {
        assertEquals(retention.cutoffDate(), source.retentionCutoff())
    }
}
