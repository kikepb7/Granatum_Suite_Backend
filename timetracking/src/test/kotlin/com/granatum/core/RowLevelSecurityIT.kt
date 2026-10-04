package com.granatum.core

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Enforces constitution principle VII for this module's tables.
 *
 * `inventory` has a test of the same name, and it does **not** cover these
 * tables: it lives in a module that depends only on `common`, so its
 * Testcontainers database receives migrations V1-V5 from its own classpath and
 * the V6-V11 tables do not exist there at all. Each module only ever sees its
 * own migrations, which is why the per-module test cannot be total and why
 * `app` additionally carries `EsquemaCompletoRlsIT`.
 *
 * It matters more here than in inventory: these tables hold national ID
 * numbers, locations and working hours, and Supabase's data API would publish
 * them.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class RowLevelSecurityIT {

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
        }
    }

    @Autowired
    lateinit var dataSource: DataSource

    /**
     * The tables this module's migrations create. Asserted by name so the test
     * cannot pass vacuously: "no table lacks RLS" is trivially true of an empty
     * schema, which is exactly what you would get if the migrations silently
     * failed to run - the failure mode this module has already been bitten by
     * once, when Flyway was not wired up at all.
     */
    private val tablasEsperadas = setOf(
        "empleados",
        "fichajes",
        "pausas",
        "solicitudes_correccion_fichaje",
        "fichaje_eventos",
        "depuraciones_retencion"
    )

    @Test
    fun `the module's migrations ran and created every expected table`() {
        val presentes = mutableSetOf<String>()

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                val rs = statement.executeQuery(
                    """
                    SELECT c.relname
                      FROM pg_class c
                      JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = 'public' AND c.relkind = 'r'
                    """.trimIndent()
                )
                while (rs.next()) presentes += rs.getString("relname")
            }
        }

        assertEquals(
            emptySet(),
            tablasEsperadas - presentes,
            "Missing tables: the migrations did not run, so the RLS assertion below " +
                "would pass over an empty schema and prove nothing"
        )
    }

    @Test
    fun `every table in the public schema has row level security enabled`() {
        val withoutRls = mutableListOf<String>()

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                val rs = statement.executeQuery(
                    """
                    SELECT c.relname
                      FROM pg_class c
                      JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = 'public'
                       AND c.relkind = 'r'
                       AND NOT c.relrowsecurity
                       -- Flyway's bookkeeping table cannot be altered from
                       -- inside a migration: Flyway holds a lock on it for the
                       -- whole run, so the ALTER would wait on a lock only
                       -- released when the run ends, and the migration
                       -- deadlocks against itself. Handled as a per-environment
                       -- ops step instead; see README.md.
                       AND c.relname <> 'flyway_schema_history'
                     ORDER BY c.relname
                    """.trimIndent()
                )
                while (rs.next()) {
                    withoutRls += rs.getString("relname")
                }
            }
        }

        assertTrue(
            withoutRls.isEmpty(),
            "These tables have no RLS, so Supabase's data API would expose them: $withoutRls. " +
                "Enable it in the same migration that creates the table."
        )
    }

    @Test
    fun `a non-owner role sees no rows while the owner still sees its data`() {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { statement ->
                // Stand-in for Supabase's `anon` / `authenticated`: holds SELECT
                // but does not own the table. Those roles do not exist on a
                // plain Postgres, so the test creates its own.
                statement.execute("DROP ROLE IF EXISTS rls_probe_tt")
                statement.execute("CREATE ROLE rls_probe_tt NOLOGIN")
                statement.execute("GRANT USAGE ON SCHEMA public TO rls_probe_tt")
                statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO rls_probe_tt")

                val id = UUID.randomUUID()
                statement.execute(
                    """
                    INSERT INTO empleados
                        (id, nombre, documento_identidad, puesto, tipo_contrato,
                         fecha_alta, activo, created_at, updated_at)
                    VALUES ('$id', 'RLS probe', 'X0000000T', 'Prueba',
                            'JORNADA_COMPLETA', CURRENT_DATE, TRUE, now(), now())
                    """.trimIndent()
                )

                try {
                    // Control: as the owner, RLS is bypassed and the row is
                    // visible. Without this the next assertion could pass
                    // simply because the insert never happened.
                    assertEquals(1, contarEmpleados(statement, id), "owner should see its own row")

                    statement.execute("SET ROLE rls_probe_tt")
                    assertEquals(
                        0,
                        contarEmpleados(statement, id),
                        "rls_probe_tt is not the owner and no policy grants it access, so RLS " +
                            "must hide every row - this is exactly what stops PostgREST from " +
                            "serving staff records with the public anon key"
                    )
                } finally {
                    statement.execute("RESET ROLE")
                    statement.execute("REVOKE SELECT ON ALL TABLES IN SCHEMA public FROM rls_probe_tt")
                    statement.execute("REVOKE USAGE ON SCHEMA public FROM rls_probe_tt")
                    statement.execute("DROP ROLE IF EXISTS rls_probe_tt")
                }
            }
        }
    }

    private fun contarEmpleados(statement: java.sql.Statement, id: UUID): Int =
        statement.executeQuery("SELECT count(*) FROM empleados WHERE id = '$id'").use { rs ->
            rs.next()
            rs.getInt(1)
        }
}
