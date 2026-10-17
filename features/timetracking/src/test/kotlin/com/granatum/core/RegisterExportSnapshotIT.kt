package com.granatum.core

import com.granatum.core.domain.model.ExportTarget
import com.granatum.core.domain.model.ShiftValues
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.service.ShiftCorrectionService
import com.granatum.core.service.RegisterExportService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * D-002: the whole export sees the database as it was when it started.
 *
 * With one fichaje per batch, the test's output stream - on receiving the first
 * data row - approves, **from another thread** and waiting for its commit, a
 * correction on a fichaje the export has not read yet. The file must show that
 * fichaje without the correction. A file mixing states from before and after a
 * change would not be the register at any moment, and two exports "with no
 * change in between" would not be comparable (FR-011).
 *
 * ## Validated by mutation (2026-10-07)
 *
 * Both mutations of `RegisterExportService` turn this test red, with the same
 * failure: `expected: <2025-03-04 15:00> but was: <2025-03-04 18:00>`.
 *
 * 1. **No single transaction**: `readTransaction.executeWithoutResult { ... }` replaced
 *    by `run { ... }`, so each query of each batch runs in its own transaction
 *    and sees the latest commit.
 * 2. **One transaction at the default isolation**: `ISOLATION_REPEATABLE_READ`
 *    replaced by `ISOLATION_READ_COMMITTED`. One transaction is not enough on
 *    its own: in Postgres, READ COMMITTED takes a new snapshot per statement,
 *    so the later batch still sees the approval.
 *
 * Restored after each run; the test is green against the real code.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class RegisterExportSnapshotIT {

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
            // One fichaje per batch, so the second one is read by a later query.
            registry.add("timetracking.export.batch-size") { "1" }
        }
    }

    @Autowired lateinit var exportService: RegisterExportService
    @Autowired lateinit var corrections: ShiftCorrectionService
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var dataSource: DataSource

    private val seed by lazy { RegisterSeed(dataSource, employeeRepository) }

    @Test
    fun `a correction approved mid-export does not reach the file`() {
        val person = seed.employee()
        val manager = seed.employee(name = "Encargado")
        seed.shift(person, "2025-03-03 07:00", "2025-03-03 15:00")
        val secondShift = seed.shift(person, "2025-03-04 07:00", "2025-03-04 15:00")
        val correctionRequest = corrections.request(
            secondShift, person.id, Role.EMPLOYEE, "Sali mas tarde",
            ShiftValues(seed.instantOf("2025-03-04 07:00"), seed.instantOf("2025-03-04 18:00"), emptyList())
        )

        val otherThread = Executors.newSingleThreadExecutor()
        var approvedRequest = false
        val output = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                super.write(b, off, len)
                // Header plus the first data row: the second fichaje is still unread.
                if (!approvedRequest && toString(Charsets.UTF_8).split("\r\n").size > 2) {
                    approvedRequest = true
                    otherThread.submit { corrections.approve(correctionRequest.id, manager.id, Role.MANAGER) }
                        .get(30, TimeUnit.SECONDS)
                }
            }
        }

        try {
            exportService.export(
                ExportTarget.Person(person.id),
                LocalDate.parse("2025-03-01"), LocalDate.parse("2025-03-31"),
                person.id, Role.EMPLOYEE, output
            )
        } finally {
            otherThread.shutdownNow()
        }

        assertEquals(true, approvedRequest, "the approval must have happened during the export")
        val row = RegisterCsvReader.rows(output.toByteArray()).single { it["Fecha"] == "2025-03-04" }
        assertEquals("2025-03-04 15:00", row["Salida"], "the snapshot from the start, not the approved value")
        assertEquals("No", row["Corregido"])
        assertEquals("", row["Correcciones"])

        // And the correction did commit: a new export sees it.
        val after = ByteArrayOutputStream()
        exportService.export(
            ExportTarget.Person(person.id),
            LocalDate.parse("2025-03-01"), LocalDate.parse("2025-03-31"),
            person.id, Role.EMPLOYEE, after
        )
        assertEquals("2025-03-04 18:00", RegisterCsvReader.rows(after.toByteArray()).single { it["Fecha"] == "2025-03-04" }["Salida"])
    }
}
