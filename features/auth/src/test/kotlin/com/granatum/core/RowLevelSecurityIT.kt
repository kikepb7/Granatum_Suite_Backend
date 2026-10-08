package com.granatum.core

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Enforces constitution principle VII for this module's tables, and doubles as
 * the proof that the V12-V14 migrations actually run against a real Postgres.
 *
 * RLS matters more here than for any other table in the system: `cuentas_acceso`
 * holds email addresses and password hashes, and Supabase publishes a REST API
 * over the `public` schema automatically - a table without RLS there is readable
 * from the internet with the anon key, which is public by design.
 *
 * `inventory` and `timetracking` each have a test of this name and neither
 * covers these tables: every module only ever sees its own migrations on its own
 * classpath, which is why the per-module test cannot be total and why `app`
 * additionally carries `EsquemaCompletoRlsIT`.
 *
 * Extends [BaseAuthIT] for the container wiring **and** for the
 * `DirectorioEmpleados` double. The double is not incidental here: the moment
 * `AutenticacionService` existed, this test stopped starting without it, which
 * is the fail-fast D-009 specified on purpose - a module that cannot check
 * whether a person exists would accept orphan accounts in silence.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class RowLevelSecurityIT : BaseAuthIT() {

    @Autowired
    lateinit var dataSource: DataSource

    /**
     * Asserted by name so the RLS test below cannot pass vacuously: "no table
     * lacks RLS" is trivially true of an empty schema, which is exactly what
     * you would get if the migrations silently failed to run - the failure mode
     * this project has already been bitten by once, when Flyway was not wired up
     * at all and `ddl-auto: validate` was the only thing that noticed.
     */
    private val tablasEsperadas = setOf(
        "cuentas_acceso",
        "sesiones_renovacion",
        "eventos_seguridad",
        // Feature 005: names, DNIs, emails and password hashes of pending sign-ups.
        "solicitudes_registro"
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
        val sinRls = mutableListOf<String>()

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
                while (rs.next()) sinRls += rs.getString("relname")
            }
        }

        assertTrue(
            sinRls.isEmpty(),
            "These tables have no RLS, so Supabase's data API would expose them: $sinRls. " +
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
                val id = UUID.randomUUID()
                // Everything inside the try, including the setup: a failure
                // before `finally` would leave the probe role granted and -
                // worse - a pooled connection with `SET ROLE` still applied,
                // which RLS then hides every row from for every later test in
                // the module. That is how one broken assertion here turned into
                // ten unrelated failures elsewhere.
                try {
                    statement.execute("DROP ROLE IF EXISTS rls_probe_auth")
                    statement.execute("CREATE ROLE rls_probe_auth NOLOGIN")
                    statement.execute("GRANT USAGE ON SCHEMA public TO rls_probe_auth")
                    statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO rls_probe_auth")

                    statement.execute(
                        """
                        INSERT INTO cuentas_acceso
                            (id, empleado_id, email, rol, password_hash,
                             requiere_cambio_password, intentos_fallidos, nivel_bloqueo,
                             created_at, updated_at)
                        VALUES ('$id', '${UUID.randomUUID()}', 'rls-probe-$id@granatum.es',
                                'EMPLEADO', '{argon2}no-es-un-hash-real', TRUE, 0, 0,
                                now(), now())
                        """.trimIndent()
                    )

                    // Control: as the owner, RLS is bypassed and the row is
                    // visible. Without this the next assertion could pass simply
                    // because the insert never happened.
                    assertEquals(1, contarCuentas(statement, id), "owner should see its own row")

                    statement.execute("SET ROLE rls_probe_auth")
                    assertEquals(
                        0,
                        contarCuentas(statement, id),
                        "rls_probe_auth is not the owner and no policy grants it access, so " +
                            "RLS must hide every row - this is what stops PostgREST from " +
                            "serving email addresses and password hashes with the public anon key"
                    )
                } finally {
                    statement.execute("RESET ROLE")
                    statement.execute("REVOKE SELECT ON ALL TABLES IN SCHEMA public FROM rls_probe_auth")
                    statement.execute("REVOKE USAGE ON SCHEMA public FROM rls_probe_auth")
                    statement.execute("DROP ROLE IF EXISTS rls_probe_auth")
                }
            }
        }
    }

    private fun contarCuentas(statement: java.sql.Statement, id: UUID): Int =
        statement.executeQuery("SELECT count(*) FROM cuentas_acceso WHERE id = '$id'").use { rs ->
            rs.next()
            rs.getInt(1)
        }
}
