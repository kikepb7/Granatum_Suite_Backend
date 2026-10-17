package com.granatum.core

import com.granatum.core.domain.exception.VerificationFileTooLargeException
import com.granatum.core.domain.model.ExportTarget
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.service.RegisterExportService
import com.granatum.core.service.RegisterExportVerification
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FR-028, SC-008: a file can be recognised by its content alone, and a single
 * changed character is enough for it not to be.
 *
 * The file is read as a stream and only hashed (D-007): nothing of it is kept,
 * and a body beyond the configured size is refused without reading the rest.
 * The test configuration caps it at 1 MiB.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class RegisterExportVerificationIT {

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
    @Autowired lateinit var verification: RegisterExportVerification
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var dataSource: DataSource

    private val seed by lazy { RegisterSeed(dataSource, employeeRepository) }

    private fun exportOne(): Pair<ByteArray, UUID> {
        val person = seed.employee(name = "Persona Verificada")
        seed.shift(person, "2025-06-02 08:00", "2025-06-02 14:00")
        val out = ByteArrayOutputStream()
        val r = exportService.export(
            ExportTarget.Person(person.id), LocalDate.parse("2025-06-01"), LocalDate.parse("2025-06-30"),
            person.id, Role.EMPLOYEE, out
        )
        return out.toByteArray() to r.exportId
    }

    @Test
    fun `a freshly exported file matches, and its export is returned`() {
        val (bytes, id) = exportOne()
        val result = verification.verify(ByteArrayInputStream(bytes))
        assertEquals(listOf(id), result.matchingExports.map { it.id })
    }

    @Test
    fun `two identical exports are both returned`() {
        val person = seed.employee(name = "Dos Veces")
        seed.shift(person, "2025-06-02 08:00", "2025-06-02 14:00")
        fun export() = ByteArrayOutputStream().also {
            exportService.export(
                ExportTarget.Person(person.id), LocalDate.parse("2025-06-01"), LocalDate.parse("2025-06-30"),
                person.id, Role.EMPLOYEE, it
            )
        }.toByteArray()
        val firstDay = export()
        export()

        assertEquals(2, verification.verify(ByteArrayInputStream(firstDay)).matchingExports.size)
    }

    /** SC-008: one character is enough. */
    @Test
    fun `the same file with one character changed does not match`() {
        val (bytes, _) = exportOne()
        val altered = bytes.copyOf()
        val i = String(bytes, Charsets.UTF_8).indexOf("14:00")
        altered[i + 1] = '5'.code.toByte()   // 14:00 -> 15:00

        assertEquals(emptyList(), verification.verify(ByteArrayInputStream(altered)).matchingExports)
    }

    @Test
    fun `a body beyond the limit is refused without reading all of it`() {
        val limit = 1_048_576L
        var readCount = 0L
        val huge = object : InputStream() {
            override fun read(): Int = if (readCount++ < limit * 10) 'x'.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (readCount >= limit * 10) return -1
                b.fill('x'.code.toByte(), off, off + len)
                readCount += len
                return len
            }
        }

        assertFailsWith<VerificationFileTooLargeException> { verification.verify(huge) }
        assertTrue(readCount < limit * 2, "stops shortly after the limit, read $readCount bytes")
    }

    /** D-007: verification keeps nothing - no table grows but the fingerprint lookup reads. */
    @Test
    fun `verifying stores nothing`() {
        val (bytes, _) = exportOne()
        val before = seed.registerFingerprint() + ("exports" to countExports())
        verification.verify(ByteArrayInputStream(bytes))
        verification.verify(ByteArrayInputStream("cualquier cosa".toByteArray()))
        assertEquals(before, seed.registerFingerprint() + ("exports" to countExports()))
    }

    private fun countExports(): String =
        dataSource.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM exports").use { r -> r.next(); r.getString(1) } }
        }
}
