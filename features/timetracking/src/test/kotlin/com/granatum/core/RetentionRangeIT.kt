package com.granatum.core

import com.granatum.core.domain.exception.InconsistentValuesException
import com.granatum.core.domain.model.ExportTarget
import com.granatum.core.domain.model.EffectiveRange
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.service.RegisterExportService
import com.granatum.core.service.RetentionPeriod
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayOutputStream
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * FR-017, D-009: a range reaching past the retention period is trimmed to the
 * boundary, and says so, so that missing rows are not read as days not worked.
 *
 * The boundary is [RetentionPeriod], the same one the purge uses: the export
 * never announces data the purge has removed, nor hides data it has kept.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class RetentionRangeIT {

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
    @Autowired lateinit var retentionPeriod: RetentionPeriod
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var dataSource: DataSource

    private val seed by lazy { RegisterSeed(dataSource, employeeRepository) }

    @Test
    fun `a range starting before the boundary is trimmed to it and marked`() {
        val person = seed.employee()
        val cutoff = retentionPeriod.cutoffDate()
        // Older than the period, and still there because the purge is off by
        // default: it must not come out as if the period did not exist.
        val oldDate = cutoff.minusDays(10)
        seed.shift(person, "$oldDate 07:00", "$oldDate 15:00")
        seed.shift(person, "$cutoff 07:00", "$cutoff 15:00")

        val out = ByteArrayOutputStream()
        val result = exportService.export(
            ExportTarget.Person(person.id), cutoff.minusYears(1), cutoff.plusDays(5),
            person.id, Role.EMPLOYEE, out
        )

        assertEquals(EffectiveRange(cutoff, cutoff.plusDays(5), trimmed = true), result.range)
        assertEquals(listOf(cutoff.toString()), RegisterCsvReader.rows(out.toByteArray()).map { it["Fecha"] })
    }

    @Test
    fun `a range inside the period is untouched`() {
        val person = seed.employee()
        val from = retentionPeriod.cutoffDate().plusDays(1)
        val to = from.plusMonths(1)

        val result = exportService.export(
            ExportTarget.Person(person.id), from, to, person.id, Role.EMPLOYEE, ByteArrayOutputStream()
        )

        assertEquals(EffectiveRange(from, to, trimmed = false), result.range)
    }

    /**
     * Nothing of the range is still within the period. An empty file would say
     * "no shifts", which is the very misreading FR-017 exists to prevent, so the
     * request is refused with the date data starts from.
     */
    @Test
    fun `a range entirely before the boundary is refused`() {
        val person = seed.employee()
        val cutoff = retentionPeriod.cutoffDate()

        val error = assertFailsWith<InconsistentValuesException> {
            exportService.export(
                ExportTarget.Person(person.id), cutoff.minusYears(1), cutoff.minusDays(1),
                person.id, Role.EMPLOYEE, ByteArrayOutputStream()
            )
        }
        assertEquals(true, error.message?.contains(cutoff.toString()))
    }
}
