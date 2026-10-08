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
 * Enforces constitution principle VII for the invoicing tables (feature 004).
 *
 * Each module's test database only receives that module's migrations (V17-V19
 * here), which is why `app` also carries `EsquemaCompletoRlsIT`. These tables
 * hold what the company buys and sells, from whom, and the NIF of self-employed
 * suppliers - which is their DNI - so Supabase's data API must not publish them.
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
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
        "empresa",
        "trimestres",
        "trimestre_eventos",
        "facturas",
        "factura_lineas_iva",
        "factura_documentos",
        "factura_reconocimientos",
        "factura_cambios"
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
                       -- deadlocks against itself. app's RlsHistorialFlyway
                       -- enables it at startup, after Flyway has finished.
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
                statement.execute("DROP ROLE IF EXISTS rls_probe_inv")
                statement.execute("CREATE ROLE rls_probe_inv NOLOGIN")
                statement.execute("GRANT USAGE ON SCHEMA public TO rls_probe_inv")
                statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO rls_probe_inv")

                // An invoice: what the company buys, from whom, for how much.
                val id = UUID.randomUUID()
                statement.execute(
                    """
                    INSERT INTO facturas
                        (id, estado, documento_sha256, subida_por, subida_en, version)
                    VALUES ('$id', 'BORRADOR', '${"a".repeat(64)}', '${UUID.randomUUID()}', now(), 0)
                    """.trimIndent()
                )

                try {
                    // Control: as the owner, RLS is bypassed and the row is
                    // visible. Without this the next assertion could pass
                    // simply because the insert never happened.
                    assertEquals(1, contarFacturas(statement, id), "owner should see its own row")

                    statement.execute("SET ROLE rls_probe_inv")
                    assertEquals(
                        0,
                        contarFacturas(statement, id),
                        "rls_probe_inv is not the owner and no policy grants it access, so RLS " +
                            "must hide every row - this is exactly what stops PostgREST from " +
                            "serving the company's invoices with the public anon key"
                    )
                } finally {
                    statement.execute("RESET ROLE")
                    statement.execute("REVOKE SELECT ON ALL TABLES IN SCHEMA public FROM rls_probe_inv")
                    statement.execute("REVOKE USAGE ON SCHEMA public FROM rls_probe_inv")
                    statement.execute("DROP ROLE IF EXISTS rls_probe_inv")
                }
            }
        }
    }

    private fun contarFacturas(statement: java.sql.Statement, id: UUID): Int =
        statement.executeQuery("SELECT count(*) FROM facturas WHERE id = '$id'").use { rs ->
            rs.next()
            rs.getInt(1)
        }
}
