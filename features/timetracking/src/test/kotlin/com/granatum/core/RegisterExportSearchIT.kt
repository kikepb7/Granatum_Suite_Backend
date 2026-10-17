package com.granatum.core

import com.granatum.core.service.RegisterExportLog
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * FR-029: the export log, filtered three ways and newest first.
 *
 * - **by person**: "who obtained X's data?". A staff export contains everyone,
 *   so it answers that too, and is included;
 * - **by generation date**: "what was exported this week?";
 * - **by period covered**, by overlap: "who obtained March's data?" - an export
 *   from February to April did, one of January did not.
 *
 * Rows are inserted directly with the dates each case needs: the service only
 * ever writes `generated_at = now`.
 */
@SpringBootTest(classes = [TimetrackingTestApplication::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegisterExportSearchIT {

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

    @Autowired lateinit var logEntry: RegisterExportLog
    @Autowired lateinit var dataSource: DataSource

    private val ana = UUID.randomUUID()
    private val luis = UUID.randomUUID()
    private val ids = mutableMapOf<String, UUID>()

    private fun insert(key: String, employeeId: UUID?, from: String, to: String, generated: String) {
        val id = UUID.randomUUID()
        ids[key] = id
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO exports (id, requester_id, requester_role, scope, employee_id,
                                           from_date, to_date, generated_at, completed, row_count, fingerprint)
                VALUES (?, ?, 'ADMIN', ?, ?, ?::date, ?::date, ?::timestamptz, TRUE, 1, ?)
                """.trimIndent()
            ).use { s ->
                s.setObject(1, id)
                s.setObject(2, UUID.randomUUID())
                s.setString(3, if (employeeId == null) "STAFF" else "PERSON")
                s.setObject(4, employeeId)
                s.setString(5, from)
                s.setString(6, to)
                s.setString(7, generated)
                s.setString(8, "a".repeat(64))
                s.executeUpdate()
            }
        }
    }

    @BeforeAll
    fun seedData() {
        insert("ana-enero", ana, "2025-01-01", "2025-01-31", "2025-02-03 10:00 Europe/Madrid")
        insert("ana-feb-abr", ana, "2025-02-01", "2025-04-30", "2025-05-05 10:00 Europe/Madrid")
        insert("luis-marzo", luis, "2025-03-01", "2025-03-31", "2025-05-06 10:00 Europe/Madrid")
        insert("plantilla-marzo", null, "2025-03-10", "2025-03-20", "2025-05-07 23:30 Europe/Madrid")
    }

    private fun search(
        employeeId: UUID? = null,
        generatedFrom: String? = null,
        generatedTo: String? = null,
        coversFrom: String? = null,
        coversTo: String? = null
    ): List<String> {
        val byId = ids.entries.associate { (k, v) -> v to k }
        return logEntry.search(
            employeeId,
            generatedFrom?.let(LocalDate::parse),
            generatedTo?.let(LocalDate::parse),
            coversFrom?.let(LocalDate::parse),
            coversTo?.let(LocalDate::parse)
        ).mapNotNull { byId[it.id] }
    }

    @Test
    fun `without filters, everything, newest first`() {
        assertEquals(listOf("plantilla-marzo", "luis-marzo", "ana-feb-abr", "ana-enero"), search())
    }

    @Test
    fun `by person, including the staff exports that contain them`() {
        assertEquals(listOf("plantilla-marzo", "ana-feb-abr", "ana-enero"), search(employeeId = ana))
        assertEquals(listOf("plantilla-marzo", "luis-marzo"), search(employeeId = luis))
    }

    /** Both ends are whole days in Madrid: the 23:30 export of May 7th is May 7th's. */
    @Test
    fun `by generation date, both ends inclusive`() {
        assertEquals(listOf("plantilla-marzo", "luis-marzo"), search(generatedFrom = "2025-05-06", generatedTo = "2025-05-07"))
        assertEquals(listOf("ana-enero"), search(generatedTo = "2025-02-03"))
    }

    @Test
    fun `by period covered, by overlap`() {
        assertEquals(
            listOf("plantilla-marzo", "luis-marzo", "ana-feb-abr"),
            search(coversFrom = "2025-03-01", coversTo = "2025-03-31"),
            "February to April covers March; January does not"
        )
        assertEquals(listOf("ana-enero"), search(coversFrom = "2025-01-31", coversTo = "2025-01-31"))
    }

    @Test
    fun `filters combine`() {
        assertEquals(listOf("ana-feb-abr"), search(employeeId = ana, coversFrom = "2025-04-01", coversTo = "2025-04-15"))
    }
}
