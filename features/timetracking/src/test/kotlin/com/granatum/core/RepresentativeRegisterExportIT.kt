package com.granatum.core

import com.granatum.core.domain.model.ExportTarget
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.infrastructure.database.repositories.RegisterExportRepository
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
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * US4: what the workers' representatives receive (FR-007, FR-012, FR-023;
 * SC-004).
 *
 * Article 34.9 entitles them to the register, not to each person's identity
 * document or whereabouts. The document column is **omitted**, not blanked; the
 * location is never exported, for any role.
 *
 * Checked on every path that writes a file - the range export for a person, for
 * the staff, and the monthly download. The monthly one goes through a different
 * service method, so testing only the staff would let a leak there through
 * (finding H1 of the analysis).
 */
@SpringBootTest(classes = [TimetrackingTestApplication::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepresentativeRegisterExportIT {

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
    @Autowired lateinit var exports: RegisterExportRepository
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var dataSource: DataSource

    private val seed by lazy { RegisterSeed(dataSource, employeeRepository) }
    private val from = LocalDate.parse("2025-06-01")
    private val to = LocalDate.parse("2025-06-30")

    private val documents = listOf("REPDOC111A", "REPDOC222B")
    // Distinctive enough that no date, time or minute count can contain them.
    private val latitudes = listOf("40.416775", "41.387917")
    private val longitudes = listOf("-3.703790", "2.169919")

    private lateinit var people: List<EmployeeEntity>

    @BeforeAll
    fun seedData() {
        people = documents.mapIndexed { i, doc ->
            seed.employee(name = "Persona $i", document = doc).also { p ->
                seed.shift(p, "2025-06-02 08:00", "2025-06-02 14:00", location = latitudes[i] to longitudes[i])
            }
        }
    }

    private fun export(role: Role, scope: ExportTarget): ByteArray {
        val out = ByteArrayOutputStream()
        exportService.export(scope, from, to, UUID.randomUUID(), role, out)
        return out.toByteArray()
    }

    private fun monthly(role: Role): ByteArray {
        val out = ByteArrayOutputStream()
        exportService.downloadMonthly(people[0].id, 2025, 6, UUID.randomUUID(), role, out)
        return out.toByteArray()
    }

    private fun withoutDocument(bytes: ByteArray, scenario: String) {
        val text = String(bytes, Charsets.UTF_8)
        assertFalse("Documento" in RegisterCsvReader.header(bytes), "$scenario: the column is omitted, not blanked")
        documents.forEach { assertFalse(text.contains(it), "$scenario: document $it must not appear") }
        val width = RegisterCsvReader.header(bytes).size
        RegisterCsvReader.records(bytes).filter { it != listOf("") }.forEach {
            assertEquals(width, it.size, "$scenario: every row as wide as the header")
        }
    }

    private fun withoutLocation(bytes: ByteArray, scenario: String) {
        val text = String(bytes, Charsets.UTF_8)
        (latitudes + longitudes).forEach { assertFalse(text.contains(it), "$scenario: coordinate $it must not appear") }
    }

    /** FR-012, SC-004 */
    @Test
    fun `REPRESENTATIVE gets no document column, on every path`() {
        withoutDocument(export(Role.REPRESENTATIVE, ExportTarget.Staff), "staff")
        withoutDocument(export(Role.REPRESENTATIVE, ExportTarget.Person(people[0].id)), "person")
        withoutDocument(monthly(Role.REPRESENTATIVE), "monthly")
        assertEquals("Persona 0", RegisterCsvReader.rows(monthly(Role.REPRESENTATIVE)).single()["Nombre"], "the register itself is there")
    }

    /** FR-007: no location, for anyone. */
    @Test
    fun `no coordinate leaves for any role, on every path`() {
        listOf(Role.REPRESENTATIVE, Role.ADMIN, Role.MANAGER).forEach { role ->
            withoutLocation(export(role, ExportTarget.Staff), "$role staff")
            withoutLocation(export(role, ExportTarget.Person(people[1].id)), "$role person")
            withoutLocation(monthly(role), "$role monthly")
        }
    }

    @Test
    fun `the other roles do get the document`() {
        val bytes = export(Role.ADMIN, ExportTarget.Staff)
        assertTrue("Documento" in RegisterCsvReader.header(bytes))
        assertEquals(documents, RegisterCsvReader.rows(bytes).map { it["Documento"] })
    }

    @Test
    fun `exporting as REPRESENTATIVE writes nothing to the register, and is recorded with that role`() {
        val before = seed.registerFingerprint()
        export(Role.REPRESENTATIVE, ExportTarget.Person(people[0].id))
        monthly(Role.REPRESENTATIVE)
        assertEquals(before, seed.registerFingerprint())

        val latest = exports.search(people[0].id, null, null, null, null).take(2)
        assertTrue(latest.all { it.requesterRole == Role.REPRESENTATIVE }, "D-012: the log says which version was handed over")
    }
}
