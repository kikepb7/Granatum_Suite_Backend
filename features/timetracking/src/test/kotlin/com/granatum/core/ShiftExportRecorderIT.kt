package com.granatum.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.granatum.core.domain.contract.ShiftExportRecorder
import com.granatum.core.domain.model.ExportTarget
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.service.RegisterExportService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feature 012, US2 (FR-012, research.md D-006): an export to the Google
 * spreadsheet is recorded in the existing export log with its destination,
 * and `/api/exports` shows the destination of every row.
 */
@Testcontainers
@SpringBootTest(
    classes = [TimetrackingTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(TimetrackingPermissiveSecurityTestConfig::class)
class ShiftExportRecorderIT {

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

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var recorder: ShiftExportRecorder
    @Autowired lateinit var exportService: RegisterExportService
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var jdbc: JdbcTemplate

    private val seed by lazy { RegisterSeed(dataSource, employeeRepository) }

    private fun exportsJson() = ObjectMapper().readTree(
        RestClient.builder().baseUrl("http://localhost:$port").build()
            .get().uri("/api/exports").retrieve().body(String::class.java)
    )

    @Test
    fun `a sheet export is recorded with its destination, the whole staff, the month, the rows and the fingerprint`() {
        val admin = UUID.randomUUID()
        val fingerprint = "ab".repeat(32)

        recorder.recordSheetExport(admin, Role.ADMIN, YearMonth.of(2026, 2), 41, fingerprint)

        val row = jdbc.queryForMap("SELECT * FROM exports WHERE requester_id = ?", admin)
        assertEquals("GOOGLE_SHEETS", row["destination"])
        // MONTHLY requires an employee (ck_exports_employee, V29): the whole
        // staff is STAFF with a null employee (research.md D-006).
        assertEquals("STAFF", row["scope"])
        assertEquals(null, row["employee_id"])
        assertEquals("ADMIN", row["requester_role"])
        assertEquals(LocalDate.parse("2026-02-01"), (row["from_date"] as java.sql.Date).toLocalDate())
        assertEquals(LocalDate.parse("2026-02-28"), (row["to_date"] as java.sql.Date).toLocalDate())
        assertEquals(true, row["completed"])
        assertEquals(41, row["row_count"])
        assertEquals(fingerprint, row["fingerprint"])

        val listed = exportsJson().first { it["requesterId"].asText() == admin.toString() }
        assertEquals("GOOGLE_SHEETS", listed["destination"].asText())
        assertEquals("STAFF", listed["scope"].asText())
    }

    @Test
    fun `a CSV export keeps the CSV destination`() {
        val person = seed.employee(name = "Ana", lastName = "Ruiz")
        seed.shift(person, "2025-03-03 07:00", "2025-03-03 15:00")
        val admin = UUID.randomUUID()

        exportService.export(
            ExportTarget.Staff, LocalDate.parse("2025-03-01"), LocalDate.parse("2025-03-31"),
            admin, Role.ADMIN, ByteArrayOutputStream()
        )

        assertEquals("CSV", jdbc.queryForObject("SELECT destination FROM exports WHERE requester_id = ?", String::class.java, admin))
        val listed = exportsJson().first { it["requesterId"].asText() == admin.toString() }
        assertEquals("CSV", listed["destination"].asText())
        assertTrue(exportsJson().all { it.has("destination") })
    }
}
