package com.granatum.core

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The only place the schema is complete.
 *
 * Each module's own `RowLevelSecurityIT` sees only the migrations on its own
 * classpath - `inventory` gets V1-V5, `timetracking` V6-V11 - so neither can
 * ever be total, and a future module that forgets to enable RLS would be caught
 * by neither. `app` aggregates every module, so this is the one test that can
 * make the claim for the whole database.
 *
 * It runs against the local Postgres that `GranatumSuiteApplicationTests`
 * already uses rather than its own container: the point is the real assembled
 * schema, which is what the application boots against.
 */
@SpringBootTest
class FullSchemaRlsIT {

    @Autowired
    lateinit var dataSource: DataSource

    /**
     * Every table the migrations create (V24 and V26 drop one each; V28 to V34 rename them
     * to English), across the seven feature
     * modules. This is the only test that sees them all together: each module's
     * own `RowLevelSecurityIT` only has that module's migrations on its
     * classpath.
     */
    private val expectedTables = setOf(
        // inventory (material_fotos went in V26, feature 010)
        "categories", "materials", "material_history",
        // timetracking
        "employees", "shifts", "shift_breaks", "shift_correction_requests",
        "shift_events", "retention_purges",
        // timetracking, feature 003: who exported whose register
        "exports",
        // auth - the tables where RLS matters most: email addresses and
        // password hashes, which Supabase would otherwise publish to anyone
        // holding the anon key
        "accounts", "refresh_sessions", "security_events",
        // invoices (feature 004): what the company buys and sells, and from
        // whom - including the DNI of self-employed suppliers
        "companies", "quarters", "quarter_events", "invoices", "invoice_vat_lines",
        "invoice_documents", "invoice_recognitions", "invoice_changes",
        // absences (feature 007): who is off and why, sick leave included
        "absences", "vacation_entitlements",
        // notifications (feature 008): who is told what about whom
        "notifications",
        // events (feature 011): events and the material they take
        "events", "event_materials"
    )

    @Test
    fun `the assembled schema contains every expected table`() {
        val present = tablesInPublic()

        assertEquals(
            emptySet(),
            expectedTables - present,
            "missing tables mean the migrations did not all run, and the RLS " +
                "assertion below would then pass over a partial schema"
        )
    }

    @Test
    fun `no table in the assembled schema is missing row level security`() {
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
                       -- Flyway's own table cannot be altered from inside a
                       -- migration: it holds a lock on it for the whole run, so
                       -- the ALTER would wait on a lock only released when the
                       -- run ends. FlywayHistoryRls enables it at
                       -- startup instead (FlywayHistoryRlsIT checks it).
                       AND c.relname <> 'flyway_schema_history'
                     ORDER BY c.relname
                    """.trimIndent()
                )
                while (rs.next()) withoutRls += rs.getString("relname")
            }
        }

        assertTrue(
            withoutRls.isEmpty(),
            "Supabase's data API publishes the public schema, so a table without " +
                "RLS is readable from the internet with the anon key - which is " +
                "public by design. Offenders: $withoutRls"
        )
    }

    /**
     * The whole point of this test living in `app`: it must see tables from more
     * than one module, or it is just a duplicate of a per-module one.
     */
    @Test
    fun `the schema really spans both feature modules`() {
        val present = tablesInPublic()

        assertTrue("materials" in present, "inventory's tables must be here")
        assertTrue("shifts" in present, "timetracking's tables must be here")
    }

    private fun tablesInPublic(): Set<String> {
        val present = mutableSetOf<String>()
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
                while (rs.next()) present += rs.getString("relname")
            }
        }
        return present
    }
}
