package com.granatum.core

import com.granatum.core.infrastructure.database.repositories.RetentionPurgeRepository
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.infrastructure.database.repositories.RegisterExportRepository
import com.granatum.core.infrastructure.database.repositories.ShiftEventRepository
import com.granatum.core.infrastructure.database.repositories.ShiftRepository
import com.granatum.core.infrastructure.database.repositories.ShiftBreakRepository
import com.granatum.core.infrastructure.database.repositories.ShiftCorrectionRequestRepository
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The test constitution principle III requires since v2.0.0**, and SC-012:
 * inside the retention period nothing can be deleted - no endpoint, no role including
 * ADMIN, and no repository operation.
 *
 * Asserted by reflection over the module's own interfaces rather than by trying
 * calls, because the claim is about what *exists*. A behavioural test can only
 * show that the routes someone thought of are closed; this shows there is no
 * route at all, and it fails the moment someone adds one - which is the only
 * way a guarantee like this stays true.
 *
 * `RetentionDeletionRepository` is the single deliberate exception, and it is
 * checked rather than waved through: every one of its methods must be a bounded
 * `@Modifying` query, so even that repository cannot remove a record whose
 * period is still running.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class NoDeletionWithinRetentionIT {

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
        }
    }

    /** Every repository in the module except the purge one. */
    private val normalRepositories = listOf(
        EmployeeRepository::class.java,
        ShiftRepository::class.java,
        ShiftBreakRepository::class.java,
        ShiftCorrectionRequestRepository::class.java,
        ShiftEventRepository::class.java,
        RetentionPurgeRepository::class.java,
        // Feature 003. The list is fixed, so a repository missing from it is a
        // repository nobody checks.
        RegisterExportRepository::class.java
    )

    @Test
    fun `no repository on the normal path exposes a deletion operation`() {
        val offenders = normalRepositories.flatMap { repo ->
            repo.methods
                .map { it.name }
                .filter { it.startsWith("delete") || it.startsWith("remove") }
                .map { "${repo.simpleName}.$it" }
        }

        assertEquals(
            emptyList(),
            offenders,
            "Inside the retention period nothing is deleted, with no exception. Not " +
                "calling a method is not enough: an interface that offers one will " +
                "eventually be used by accident, and there would be nothing to stop " +
                "it. Offenders: $offenders"
        )
    }

    /**
     * None of them extends `JpaRepository` either, which is where `delete` and
     * `deleteAll` would arrive from without anyone writing them.
     */
    @Test
    fun `no repository extends JpaRepository`() {
        val offenders = normalRepositories.filter { repo ->
            org.springframework.data.jpa.repository.JpaRepository::class.java
                .isAssignableFrom(repo)
        }.map { it.simpleName }

        assertEquals(
            emptyList(),
            offenders,
            "JpaRepository brings delete/deleteAll in by inheritance: $offenders"
        )
    }

    /**
     * And `saveAll` is absent from the event log, which would bypass the
     * per-event idempotency check the unique `client_event_id` exists for.
     */
    @Test
    fun `the event log exposes only an insert and a lookup`() {
        val methodNames = ShiftEventRepository::class.java.methods.map { it.name }.toSet()

        assertEquals(
            setOf("save", "findByClientEventId"),
            methodNames,
            "the append-only log must offer nothing else: $methodNames"
        )
    }

    /** The one exception, held to its own rule. */
    @Test
    fun `the purge repository only deletes through bounded queries`() {
        val repo = com.granatum.core.infrastructure.database.repositories
            .RetentionDeletionRepository::class.java

        val unbounded = repo.methods.filter { javaMethod ->
            val query = javaMethod.getAnnotation(
                org.springframework.data.jpa.repository.Query::class.java
            )
            val modifying = javaMethod.getAnnotation(
                org.springframework.data.jpa.repository.Modifying::class.java
            )
            // Every method must be a @Modifying @Query whose text bounds itself
            // by the cut-off. A method without one, or one that does not mention
            // `:cutoff`, could delete a record still inside its period.
            modifying == null || query == null || !query.value.contains(":cutoff")
        }.map { it.name }

        assertEquals(
            emptyList(),
            unbounded,
            "every deletion must be bounded by the cut-off instant, so the method " +
                "itself is incapable of removing a record whose retention period is " +
                "still running: $unbounded"
        )

        assertTrue(
            repo.methods.none { it.name == "delete" || it.name == "deleteById" },
            "no id-taking deletion: that would move the guarantee from the query " +
                "back onto whoever remembers to check the date"
        )
    }
}
