package com.granatum.core

import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.model.ExportTarget
import com.granatum.core.domain.type.ExportScope
import com.granatum.core.domain.type.Role
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * US3: the whole staff in one file (FR-014, FR-015; SC-011).
 *
 * Everyone with shifts in the range appears, including people who have left -
 * the duty to keep and hand over their register does not end with the
 * contract. Ordered by name the way a Spanish reader expects, then by id.
 */
@SpringBootTest(classes = [TimetrackingTestApplication::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaffRegisterExportIT {

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

    @BeforeAll
    fun seedData() {
        // Inserted out of order on purpose.
        listOf("Zoe", "Pablo", "Óscar", "Ángel", "Nuria").forEach { name ->
            val p = seed.employee(name = name)
            seed.shift(p, "2025-06-02 08:00", "2025-06-02 14:00")
        }
        // Left the company, still within the retention period (FR-015).
        val deactivated = seed.employee(name = "Bea Baja", active = false)
        seed.shift(deactivated, "2025-06-03 08:00", "2025-06-03 14:00")
        // No shift in the range: no row.
        val outside = seed.employee(name = "Fuera Delrango")
        seed.shift(outside, "2025-05-30 08:00", "2025-05-30 14:00")
    }

    private fun export(role: Role = Role.ADMIN, scope: ExportTarget = ExportTarget.Staff): ByteArray {
        val out = ByteArrayOutputStream()
        exportService.export(scope, from, to, UUID.randomUUID(), role, out)
        return out.toByteArray()
    }

    @Test
    fun `everyone with shifts in the range appears, including someone who has left`() {
        val people = RegisterCsvReader.rows(export()).map { it["Persona"] }
        assertEquals(6, people.size)
        assertEquals(true, "Bea Baja" in people, "SC-011: leaving does not end the register")
        assertEquals(false, "Fuera Delrango" in people)
    }

    /**
     * Spanish collation: accents sort with their letter, so `Ángel` comes
     * first and `Óscar` between `Nuria` and `Pablo`. A plain code-point sort
     * would put both after `Zoe`.
     */
    @Test
    fun `people are ordered by name with Spanish collation`() {
        assertEquals(
            listOf("Ángel", "Bea Baja", "Nuria", "Óscar", "Pablo", "Zoe"),
            RegisterCsvReader.rows(export()).map { it["Persona"] }
        )
    }

    /** FR-014 */
    @Test
    fun `MANAGER, ADMIN and REPRESENTATIVE may export the staff, EMPLOYEE may not`() {
        listOf(Role.MANAGER, Role.ADMIN, Role.REPRESENTATIVE).forEach { role ->
            assertEquals(6, RegisterCsvReader.rows(export(role)).size, "$role")
        }
        assertFailsWith<ForbiddenException> { export(Role.EMPLOYEE) }
    }

    @Test
    fun `a staff export is recorded as STAFF without a person`() {
        val before = exports.search(null, null, null, null, null).count { it.scope == ExportScope.STAFF }
        export()
        val staff = exports.search(null, null, null, null, null).filter { it.scope == ExportScope.STAFF }
        assertEquals(before + 1, staff.size)
        assertNull(staff.first().employeeId)
        assertEquals(6, staff.first().rowCount)
    }
}
