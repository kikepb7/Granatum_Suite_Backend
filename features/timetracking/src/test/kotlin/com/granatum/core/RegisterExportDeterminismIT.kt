package com.granatum.core

import com.granatum.core.domain.model.ExportTarget
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
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
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * FR-011, SC-008: the same scope and range, with nothing changed in between,
 * give the same file byte for byte - and so the same fingerprint, which is
 * what lets a file be recognised later.
 *
 * ## Validated by mutation (2026-10-07)
 *
 * Each mutation of `RegisterExportService` turned this class red, and was then
 * restored:
 *
 * - **(a) No tie-break by id**: `.thenBy { it.id.toString() }` removed from the
 *   staff ordering. The same-name test failed with
 *   `expected: <[DOC-MISMO-1, DOC-MISMO-0]> but was: <[DOC-MISMO-0, DOC-MISMO-1]>`:
 *   without the tie-break the order is whatever the database returns.
 * - **(b) Generation time inside the file**: a `Generado;<instant>` line written
 *   after the header. Both tests failed with `Array elements differ at index
 *   227` - the first byte of the timestamp.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class RegisterExportDeterminismIT {

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
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var dataSource: DataSource

    private val seed by lazy { RegisterSeed(dataSource, employeeRepository) }
    private val from = LocalDate.parse("2025-06-01")
    private val to = LocalDate.parse("2025-06-30")

    private fun export(): Pair<ByteArray, String> {
        val out = ByteArrayOutputStream()
        val r = exportService.export(ExportTarget.Staff, from, to, UUID.randomUUID(), Role.ADMIN, out)
        return out.toByteArray() to r.fingerprint
    }

    @Test
    fun `two exports with nothing changed in between are identical`() {
        listOf("Marta", "Luis", "Ana").forEach { name ->
            val p = seed.employee(name = name)
            seed.shift(p, "2025-06-02 08:00", "2025-06-02 14:00")
            seed.shift(p, "2025-06-03 08:00", "2025-06-03 14:00")
        }

        val (firstDay, fingerprint1) = export()
        val (secondShift, fingerprint2) = export()

        assertContentEquals(firstDay, secondShift, "no generation time or any other moving part inside the file")
        assertEquals(fingerprint1, fingerprint2)
    }

    /**
     * Two people with the same name: the tie is broken by id, whatever order
     * they were stored in. They are inserted larger id first, so an order that
     * merely followed the database would come out reversed.
     */
    @Test
    fun `people with the same name are ordered by id, not by how they were stored`() {
        val ids = listOf(UUID.fromString("ffffffff-0000-0000-0000-000000000001"), UUID.fromString("00000000-0000-0000-0000-000000000002"))
        ids.forEachIndexed { i, id ->
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO employees (id, name, identity_document, position, contract_type,
                                           hire_date, active, created_at, updated_at)
                    VALUES (?, 'Mismo Nombre', ?, 'Florista', 'FULL_TIME', '2020-01-01', TRUE, now(), now())
                    """.trimIndent()
                ).use { s ->
                    s.setObject(1, id)
                    s.setString(2, "DOC-MISMO-$i")
                    s.executeUpdate()
                }
            }
            seed.shift(employeeRepository.findById(id).get(), "2025-06-10 08:00", "2025-06-10 14:00")
        }

        val (bytes, _) = export()
        val documents = RegisterCsvReader.rows(bytes).filter { it["Nombre"] == "Mismo Nombre" }.map { it["Documento"] }
        assertEquals(
            listOf("DOC-MISMO-1", "DOC-MISMO-0"),
            documents,
            "00000000-… before ffffffff-…, although ffffffff-… was stored first"
        )
        assertContentEquals(bytes, export().first)
    }
}
