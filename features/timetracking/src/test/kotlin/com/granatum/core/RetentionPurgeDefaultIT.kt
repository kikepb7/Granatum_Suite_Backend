package com.granatum.core

import com.granatum.core.infrastructure.database.repositories.RetentionPurgeRepository
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.scheduling.RetentionPurgeJob
import com.granatum.core.service.RetentionPeriod
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-024: shipping the monthly download does **not** switch the purge on.
 *
 * Feature 003 meets the condition that kept the purge disabled (FR-031d of
 * feature 001). What has to stay fixed is that meeting it changes nothing by
 * itself: enabling the one operation that destroys records with legal value is
 * an explicit decision per environment. Until now no test pinned the default;
 * `RetentionIT` only exercises the purge switched on.
 *
 * No `timetracking.retention.*` property is set here, on purpose.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class RetentionPurgeDefaultIT {

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

    @Autowired lateinit var job: RetentionPurgeJob
    @Autowired lateinit var purges: RetentionPurgeRepository
    @Autowired lateinit var retentionPeriod: RetentionPeriod
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var dataSource: DataSource

    @Test
    fun `without configuration the scheduled purge deletes nothing, even past the period`() {
        val seed = RegisterSeed(dataSource, employeeRepository)
        val person = seed.employee()
        val expiredDate = retentionPeriod.cutoffDate().minusYears(1)
        seed.shift(person, "$expiredDate 07:00", "$expiredDate 15:00")
        val before = seed.registerFingerprint()
        assertTrue(before.getValue("shifts").startsWith("1/"), "an expired shift is there to be purged")

        // The scheduled entry point, not ejecutar(): the switch is what is under test.
        job.purge()

        assertEquals(before, seed.registerFingerprint(), "nothing deleted")
        assertEquals(emptyList(), purges.findAllByOrderByExecutedAtDesc(), "and no run recorded")
    }
}
