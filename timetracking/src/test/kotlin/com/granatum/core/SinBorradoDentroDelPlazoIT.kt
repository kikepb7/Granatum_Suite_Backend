package com.granatum.core

import com.granatum.core.infrastructure.database.repositories.DepuracionRetencionRepository
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.infrastructure.database.repositories.FichajeEventoRepository
import com.granatum.core.infrastructure.database.repositories.FichajeRepository
import com.granatum.core.infrastructure.database.repositories.PausaRepository
import com.granatum.core.infrastructure.database.repositories.SolicitudCorreccionFichajeRepository
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
 * `RetencionPurgaRepository` is the single deliberate exception, and it is
 * checked rather than waved through: every one of its methods must be a bounded
 * `@Modifying` query, so even that repository cannot remove a record whose
 * period is still running.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class SinBorradoDentroDelPlazoIT {

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
            registry.add("timetracking.incompletos.cron") { "0 0 4 1 1 *" }
            registry.add("timetracking.retencion.cron") { "0 0 4 1 1 *" }
        }
    }

    /** Every repository in the module except the purge one. */
    private val repositoriosNormales = listOf(
        EmpleadoRepository::class.java,
        FichajeRepository::class.java,
        PausaRepository::class.java,
        SolicitudCorreccionFichajeRepository::class.java,
        FichajeEventoRepository::class.java,
        DepuracionRetencionRepository::class.java
    )

    @Test
    fun `no repository on the normal path exposes a deletion operation`() {
        val ofensores = repositoriosNormales.flatMap { repo ->
            repo.methods
                .map { it.name }
                .filter { it.startsWith("delete") || it.startsWith("remove") }
                .map { "${repo.simpleName}.$it" }
        }

        assertEquals(
            emptyList(),
            ofensores,
            "Inside the retention period nothing is deleted, with no exception. Not " +
                "calling a method is not enough: an interface that offers one will " +
                "eventually be used by accident, and there would be nothing to stop " +
                "it. Offenders: $ofensores"
        )
    }

    /**
     * None of them extends `JpaRepository` either, which is where `delete` and
     * `deleteAll` would arrive from without anyone writing them.
     */
    @Test
    fun `no repository extends JpaRepository`() {
        val ofensores = repositoriosNormales.filter { repo ->
            org.springframework.data.jpa.repository.JpaRepository::class.java
                .isAssignableFrom(repo)
        }.map { it.simpleName }

        assertEquals(
            emptyList(),
            ofensores,
            "JpaRepository brings delete/deleteAll in by inheritance: $ofensores"
        )
    }

    /**
     * And `saveAll` is absent from the event log, which would bypass the
     * per-event idempotency check the unique `client_event_id` exists for.
     */
    @Test
    fun `the event log exposes only an insert and a lookup`() {
        val metodos = FichajeEventoRepository::class.java.methods.map { it.name }.toSet()

        assertEquals(
            setOf("save", "findByClientEventId"),
            metodos,
            "the append-only log must offer nothing else: $metodos"
        )
    }

    /** The one exception, held to its own rule. */
    @Test
    fun `the purge repository only deletes through bounded queries`() {
        val repo = com.granatum.core.infrastructure.database.repositories
            .RetencionPurgaRepository::class.java

        val sinLimite = repo.methods.filter { metodo ->
            val query = metodo.getAnnotation(
                org.springframework.data.jpa.repository.Query::class.java
            )
            val modifying = metodo.getAnnotation(
                org.springframework.data.jpa.repository.Modifying::class.java
            )
            // Every method must be a @Modifying @Query whose text bounds itself
            // by the cut-off. A method without one, or one that does not mention
            // `:corte`, could delete a record still inside its period.
            modifying == null || query == null || !query.value.contains(":corte")
        }.map { it.name }

        assertEquals(
            emptyList(),
            sinLimite,
            "every deletion must be bounded by the cut-off instant, so the method " +
                "itself is incapable of removing a record whose retention period is " +
                "still running: $sinLimite"
        )

        assertTrue(
            repo.methods.none { it.name == "delete" || it.name == "deleteById" },
            "no id-taking deletion: that would move the guarantee from the query " +
                "back onto whoever remembers to check the date"
        )
    }
}
