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
 * Enforces principle VII of the constitution: every table has Row Level
 * Security enabled, so Supabase's auto-generated REST API (PostgREST) exposes
 * nothing to the anon or authenticated roles.
 *
 * Two separate things are checked, because "RLS is enabled" and "RLS actually
 * denies anyone" are not the same claim:
 *
 *  1. No table in `public` is missing RLS. This is what catches a *future*
 *     migration that creates a table and forgets to enable it - the whole
 *     point of having this test rather than trusting review.
 *  2. A role that is not the table owner really does see zero rows, while the
 *     owner still sees its data. This proves the mechanism works and that the
 *     backend (which connects as owner) is not locked out.
 */
@Testcontainers
@SpringBootTest(classes = [InventoryTestApplication::class])
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
                       -- Flyway's bookkeeping table is excluded because it
                       -- cannot be altered from inside a migration: Flyway
                       -- locks it for the whole run, so the ALTER would wait
                       -- on a lock Flyway only releases when the run ends.
                       -- Handled as a per-environment ops step instead;
                       -- see V5__enable_row_level_security.sql.
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
                "Enable it in the same migration that creates the table " +
                "(see V5__enable_row_level_security.sql)."
        )
    }

    @Test
    fun `a non-owner role sees no rows while the owner still sees its data`() {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { statement ->
                // Stand-in for Supabase's `anon` / `authenticated`: a role that
                // holds SELECT but does not own the table. Those roles do not
                // exist on a plain Postgres, so the test creates its own.
                statement.execute("DROP ROLE IF EXISTS rls_probe")
                statement.execute("CREATE ROLE rls_probe NOLOGIN")
                statement.execute("GRANT USAGE ON SCHEMA public TO rls_probe")
                statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO rls_probe")

                val id = UUID.randomUUID()
                statement.execute(
                    """
                    INSERT INTO categorias (id, nombre, descripcion, created_at, updated_at)
                    VALUES ('$id', 'RLS probe', 'fila de prueba', now(), now())
                    """.trimIndent()
                )

                try {
                    // Control: as the owner, RLS is bypassed and the row is visible.
                    // Without this the next assertion could pass simply because
                    // the insert never happened.
                    assertEquals(1, countCategorias(statement, id), "owner should see its own row")

                    statement.execute("SET ROLE rls_probe")
                    assertEquals(
                        0,
                        countCategorias(statement, id),
                        "rls_probe is not the owner and there is no policy granting it access, " +
                            "so RLS must hide every row - this is exactly what stops PostgREST " +
                            "from serving the table with the public anon key"
                    )
                } finally {
                    statement.execute("RESET ROLE")
                    statement.execute("REVOKE SELECT ON ALL TABLES IN SCHEMA public FROM rls_probe")
                    statement.execute("REVOKE USAGE ON SCHEMA public FROM rls_probe")
                    statement.execute("DROP ROLE IF EXISTS rls_probe")
                }
            }
        }
    }

    private fun countCategorias(statement: java.sql.Statement, id: UUID): Int =
        statement.executeQuery("SELECT count(*) FROM categorias WHERE id = '$id'").use { rs ->
            rs.next()
            rs.getInt(1)
        }
}
