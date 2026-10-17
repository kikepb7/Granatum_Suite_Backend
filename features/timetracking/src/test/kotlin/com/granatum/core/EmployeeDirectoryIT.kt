package com.granatum.core

import com.granatum.core.domain.contract.EmployeeDirectory
import com.granatum.core.domain.type.EmployeeStatus
import com.granatum.core.domain.type.ContractType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
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
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * This module's side of the `EmployeeDirectory` contract, which is how `auth`
 * learns whether a person exists and is employed without depending on
 * `timetracking` (constitution principle I).
 *
 * The query-count assertion is not decoration. The contract states that
 * `existing` must answer in a single query, because the orphan-account sweep
 * in `auth` walks every account: implemented as a loop over `status` it would be
 * a guaranteed N+1, and nothing in `auth` could detect that from the other side
 * of the interface. The count is taken from Hibernate's statistics, the same way
 * feature 001 asserts its own fetch plans.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class EmployeeDirectoryIT {

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
            registry.add("spring.jpa.properties.hibernate.generate_statistics") { "true" }
            registry.add("timetracking.incomplete.cron") { "0 0 4 1 1 *" }
            registry.add("timetracking.retention.cron") { "0 0 4 1 1 *" }
        }
    }

    @Autowired lateinit var directory: EmployeeDirectory
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var entityManagerFactory: EntityManagerFactory

    private val statistics
        get() = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    private fun newEmployee(active: Boolean): EmployeeEntity =
        employeeRepository.save(
            EmployeeEntity(
                name = "Persona de prueba",
                identityDocument = "T${UUID.randomUUID().toString().take(8).uppercase()}",
                position = "Florista",
                contractType = ContractType.FULL_TIME,
                hireDate = LocalDate.parse("2026-10-01"),
                active = active
            )
        )

    @Test
    fun `an active person reports ACTIVE`() {
        val employee = newEmployee(active = true)
        assertEquals(EmployeeStatus.ACTIVE, directory.status(employee.id))
    }

    /** FR-002 and FR-010 both hang off this answer. */
    @Test
    fun `a deactivated person reports INACTIVE`() {
        val employee = newEmployee(active = false)
        assertEquals(EmployeeStatus.INACTIVE, directory.status(employee.id))
    }

    /**
     * The case FR-029c is about. `null` and not an exception: "this person does
     * not exist" is an answer the caller has to act on, not a failure.
     */
    @Test
    fun `an unknown id reports null rather than throwing`() {
        assertNull(directory.status(UUID.randomUUID()))
    }

    @Test
    fun `existing keeps only the ids that exist`() {
        val one = newEmployee(active = true)
        val another = newEmployee(active = false)
        val ghost = UUID.randomUUID()

        val present = directory.existing(listOf(one.id, another.id, ghost))

        assertEquals(setOf(one.id, another.id), present)
    }

    /**
     * An inactive person still *exists*. Confusing the two would make the
     * orphan sweep report everyone on leave as an orphan account, and the
     * obvious "fix" would be to delete their credentials - and with them their
     * access to a working-time record the law requires be available to them.
     */
    @Test
    fun `existing does not filter by employment state`() {
        val inactive = newEmployee(active = false)
        assertEquals(setOf(inactive.id), directory.existing(listOf(inactive.id)))
    }

    @Test
    fun `existing answers in a single query for many ids`() {
        val ids = (1..20).map { newEmployee(active = true).id }

        statistics.clear()
        val present = directory.existing(ids)

        assertEquals(ids.toSet(), present)
        assertEquals(
            1,
            statistics.prepareStatementCount,
            "the contract requires one query: the orphan sweep in `auth` walks every " +
                "account, so a loop over `estado` would be a guaranteed N+1 that nothing " +
                "on the other side of the interface could detect"
        )
    }

    /** `IN ()` is a syntax error in Postgres, so the empty case must not reach it. */
    @Test
    fun `an empty input issues no query at all`() {
        statistics.clear()
        assertEquals(emptySet(), directory.existing(emptyList()))
        assertEquals(0, statistics.prepareStatementCount)
    }
}
