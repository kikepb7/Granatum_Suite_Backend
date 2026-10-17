package com.granatum.core

import com.granatum.core.domain.model.ExportTarget
import com.granatum.core.domain.type.ExportScope
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.infrastructure.database.repositories.RegisterExportRepository
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
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-025, FR-026, SC-007: every export leaves exactly one row, complete or
 * not, and the row holds nothing that was exported.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class RegisterExportLogIT {

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

    @Autowired lateinit var exportService: RegisterExportService
    @Autowired lateinit var exports: RegisterExportRepository
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var dataSource: DataSource

    private val seed by lazy { RegisterSeed(dataSource, employeeRepository) }
    private val from = LocalDate.parse("2025-03-01")
    private val to = LocalDate.parse("2025-03-31")

    /** A person with [days] closed shifts in March 2025. */
    private fun personWithDays(days: Int): EmployeeEntity {
        val person = seed.employee(name = "Nombre Distintivo", document = "D${UUID.randomUUID().toString().take(8)}")
        (1..days).forEach { d ->
            val day = "2025-03-${d.toString().padStart(2, '0')}"
            seed.shift(person, "$day 07:00", "$day 15:00")
        }
        return person
    }

    private fun recordsOf(person: EmployeeEntity) =
        exports.search(person.id, null, null, null, null)

    @Test
    fun `a complete export leaves one row with the row count and the fingerprint of the bytes sent`() {
        val person = personWithDays(5)
        val out = ByteArrayOutputStream()

        val result = exportService.export(
            ExportTarget.Person(person.id), from, to, person.id, Role.EMPLOYEE, out
        )

        val row = recordsOf(person).single()
        assertEquals(result.exportId, row.id)
        assertTrue(row.completed)
        assertEquals(5, row.rowCount, "data rows only, not the header")
        assertEquals(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray())),
            row.fingerprint,
            "SHA-256 of exactly what the client received, BOM included"
        )
        assertEquals(ExportScope.PERSON, row.scope)
        assertEquals(person.id, row.employeeId)
        assertEquals(person.id, row.requesterId)
        assertEquals(Role.EMPLOYEE, row.requesterRole)
        assertEquals(from, row.from)
        assertEquals(to, row.to)
    }

    /**
     * A client that drops the connection mid-file. Each write is accepted whole
     * or refused whole, so the rows the client really has are the complete
     * records in what was accepted.
     */
    private class OutputThatBreaks(private val limit: Int) : OutputStream() {
        val accepted = ByteArrayOutputStream()
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (accepted.size() + len > limit) throw IOException("Broken pipe")
            accepted.write(b, off, len)
        }
    }

    @Test
    fun `an interrupted export leaves one row, not completed, with the rows written and no fingerprint`() {
        val person = personWithDays(20)
        val output = OutputThatBreaks(limit = 900)

        assertFailsWith<IOException> {
            exportService.export(
                ExportTarget.Person(person.id), from, to, person.id, Role.EMPLOYEE, output
            )
        }

        val row = recordsOf(person).single()
        assertFalse(row.completed)
        assertNull(row.fingerprint, "a half file identifies nothing anyone holds")
        val received = RegisterCsvReader.records(output.accepted.toByteArray()).size - 1
        assertTrue(received in 1 until 20, "the cut must fall mid-file for this test to mean anything: $received")
        assertEquals(received, row.rowCount)
    }

    /** FR-026: identifiers, range, count and fingerprint. Nothing that was exported. */
    @Test
    fun `the row holds no names, documents or times`() {
        val person = personWithDays(3)
        exportService.export(
            ExportTarget.Person(person.id), from, to, person.id, Role.EMPLOYEE, ByteArrayOutputStream()
        )

        val values = dataSource.connection.use { c ->
            c.prepareStatement("SELECT * FROM exports WHERE employee_id = ?").use { s ->
                s.setObject(1, person.id)
                s.executeQuery().use { r ->
                    r.next()
                    (1..r.metaData.columnCount)
                        // When it was generated is the one time the row should hold.
                        .filter { r.metaData.getColumnName(it) != "generated_at" }
                        .map { r.getString(it).orEmpty() }
                }
            }
        }
        val joined = values.joinToString("|")
        assertFalse(joined.contains("Nombre Distintivo"))
        assertFalse(joined.contains(person.identityDocument))
        assertFalse(joined.contains("07:00") || joined.contains("15:00") || joined.contains("8:00"), joined)
    }
}
