package com.granatum.core

import com.granatum.core.domain.type.ContractType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.RetentionPurgeRepository
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.scheduling.RetentionPurgeJob
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
 * SC-013: once the four years are fully up the records are purged, and **only**
 * those.
 *
 * Rows are inserted straight through JDBC with the dates the test needs. The
 * service cannot create a shift four years old - the clock validator refuses
 * anything beyond 72 hours, correctly - so going through it would make this
 * test impossible to write rather than more faithful.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class RetentionIT {

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
            // Enabled only here. In a real environment it stays off until the
            // monthly download exists (FR-031d).
            registry.add("timetracking.retention.enabled") { "true" }
        }
    }

    @Autowired lateinit var job: RetentionPurgeJob
    @Autowired lateinit var purges: RetentionPurgeRepository
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var dataSource: DataSource

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

    /** Inserts a closed shift with a break, an event and a correction, at [daysAgo]. */
    private fun oldShift(employee: EmployeeEntity, daysAgo: Long): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.createStatement().use { s ->
                val clockIn = "now() - interval '$daysAgo days'"
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
                    INSERT INTO shift_breaks (id, shift_id, type, started_at, ended_at)
                    VALUES ('${UUID.randomUUID()}', '$id', 'MEAL',
                            $clockIn + interval '4 hours', $clockIn + interval '4 hours 30 minutes')
                    """.trimIndent()
                )
                s.execute(
                    """
                    INSERT INTO shift_events
                        (id, client_event_id, employee_id, shift_id, operation_type,
                         occurred_at, received_at, request_fingerprint, response_status,
                         response_body)
                    VALUES ('${UUID.randomUUID()}', '${UUID.randomUUID()}', '${employee.id}',
                            '$id', 'CLOCK_IN', $clockIn, $clockIn, 'abc', 201, '{}'::jsonb)
                    """.trimIndent()
                )
                s.execute(
                    """
                    INSERT INTO shift_correction_requests
                        (id, shift_id, requester_id, reason, proposed_values,
                         status, created_at)
                    VALUES ('${UUID.randomUUID()}', '$id', '${employee.id}',
                            'Motivo de prueba para retencion', '{}'::jsonb, 'PENDING', now())
                    """.trimIndent()
                )
            }
        }
        return id
    }

    private fun countRows(table: String, column: String, id: UUID): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM $table WHERE $column = ?")
                .use { s ->
                    s.setObject(1, id)
                    val rs = s.executeQuery()
                    rs.next()
                    rs.getInt(1)
                }
        }

    @Test
    fun `records past the four years are purged and the run is recorded`() {
        val employee = newEmployee()
        // Comfortably past four years (4 years + ~1 month).
        val expiredDate = oldShift(employee, 1490)

        val logEntry = job.run()

        assertEquals(0, countRows("shifts", "id", expiredDate), "the shift must be gone")
        assertEquals(0, countRows("shift_breaks", "shift_id", expiredDate))
        assertEquals(0, countRows("shift_events", "shift_id", expiredDate))
        assertEquals(0, countRows("shift_correction_requests", "shift_id", expiredDate))

        assertTrue(logEntry.deletedShifts >= 1, "the audit row must carry the count")
        assertTrue(logEntry.deletedBreaks >= 1)
        assertTrue(logEntry.deletedEvents >= 1)
        assertTrue(logEntry.deletedRequests >= 1)

        val history = purges.findAllByOrderByExecutedAtDesc()
        assertTrue(history.isNotEmpty(), "the run must leave a recoverable record")
    }

    /**
     * The boundary that matters: "fully elapsed" means exactly that. A record a
     * month short of four years is untouchable, and a purge that took it would
     * be destroying evidence the law still requires.
     */
    @Test
    fun `a record still inside the period is untouched`() {
        val employee = newEmployee()
        // Four years minus roughly a month.
        val inForce = oldShift(employee, 1430)

        job.run()

        assertEquals(1, countRows("shifts", "id", inForce), "a month short of four years survives")
        assertEquals(1, countRows("shift_breaks", "shift_id", inForce))
        assertEquals(1, countRows("shift_events", "shift_id", inForce))
        assertEquals(1, countRows("shift_correction_requests", "shift_id", inForce))
    }

    @Test
    fun `a run with nothing to purge still records itself with zeroes`() {
        val employee = newEmployee()
        oldShift(employee, 10)

        val logEntry = job.run()

        assertEquals(0, logEntry.deletedShifts)
        assertEquals(0, logEntry.deletedBreaks)
        assertTrue(
            purges.findAllByOrderByExecutedAtDesc().isNotEmpty(),
            "an empty run is still evidence the process ran, which is what an " +
                "inspection would ask about"
        )
    }

    /** An export log row covering `[today - fromDays, today - toDays]`. */
    private fun oldExport(fromDays: Long, toDays: Long): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.createStatement().use { s ->
                s.execute(
                    """
                    INSERT INTO exports (id, requester_id, requester_role, scope, employee_id,
                                               from_date, to_date, generated_at, completed, row_count, fingerprint)
                    VALUES ('$id', '${UUID.randomUUID()}', 'ADMIN', 'STAFF', NULL,
                            current_date - $fromDays, current_date - $toDays, now(), FALSE, 0, NULL)
                    """.trimIndent()
                )
            }
        }
        return id
    }

    /**
     * D-013, FR-027: an export row goes when nothing it covers is left - its
     * `to` is before the cut-off - and not a day earlier. One that still
     * covers a single day inside the period is evidence of who obtained data
     * that still exists.
     */
    @Test
    fun `an export whose whole range is past the period is purged and counted, one still covering the period is not`() {
        val expired = oldExport(1600, 1490)
        val acrossMidnight = oldExport(1500, 1430)

        val logEntry = job.run()

        assertEquals(0, countRows("exports", "id", expired), "its whole range is past the period")
        assertEquals(1, countRows("exports", "id", acrossMidnight), "it still covers days inside the period")
        assertTrue(logEntry.deletedExports >= 1, "the audit row must carry the count")
    }

    /** The audit row holds counts and a date, never personal data. */
    @Test
    fun `the purge record carries no personal data`() {
        val employee = newEmployee()
        oldShift(employee, 1490)

        job.run()

        dataSource.connection.use { connection ->
            connection.createStatement().use { s ->
                val rs = s.executeQuery(
                    """
                    SELECT column_name
                      FROM information_schema.columns
                     WHERE table_name = 'retention_purges'
                    """.trimIndent()
                )
                val columns = mutableListOf<String>()
                while (rs.next()) columns += rs.getString("column_name")

                val suspicious = columns.filter { c ->
                    c.contains("employee") || c.contains("document") ||
                        c.contains("latitude") || c.contains("longitude") || c.contains("shift_id")
                }
                assertEquals(
                    emptyList(),
                    suspicious,
                    "this row outlives the retention period it exists to honour, so " +
                        "personal data here would defeat its own purpose: $suspicious"
                )
            }
        }
    }
}
