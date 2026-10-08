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
class EsquemaCompletoRlsIT {

    @Autowired
    lateinit var dataSource: DataSource

    /**
     * Every table the twenty-three migrations create, across the six feature
     * modules. This is the only test that sees them all together: each module's
     * own `RowLevelSecurityIT` only has that module's migrations on its
     * classpath.
     */
    private val tablasEsperadas = setOf(
        // inventory
        "categorias", "materiales", "material_fotos", "historial_material",
        // timetracking
        "empleados", "fichajes", "pausas", "solicitudes_correccion_fichaje",
        "fichaje_eventos", "depuraciones_retencion",
        // timetracking, feature 003: who exported whose register
        "exportaciones",
        // auth - the tables where RLS matters most: email addresses and
        // password hashes, which Supabase would otherwise publish to anyone
        // holding the anon key
        "cuentas_acceso", "sesiones_renovacion", "eventos_seguridad",
        // invoices (feature 004): what the company buys and sells, and from
        // whom - including the DNI of self-employed suppliers
        "empresa", "trimestres", "trimestre_eventos", "facturas", "factura_lineas_iva",
        "factura_documentos", "factura_reconocimientos", "factura_cambios",
        // auth, feature 005 (V20)
        "solicitudes_registro",
        // absences (feature 007): who is off and why, sick leave included
        "ausencias", "derechos_vacaciones",
        // notifications (feature 008): who is told what about whom
        "notificaciones"
    )

    @Test
    fun `the assembled schema contains every expected table`() {
        val presentes = tablasEnPublic()

        assertEquals(
            emptySet(),
            tablasEsperadas - presentes,
            "missing tables mean the migrations did not all run, and the RLS " +
                "assertion below would then pass over a partial schema"
        )
    }

    @Test
    fun `no table in the assembled schema is missing row level security`() {
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
                       -- Flyway's own table cannot be altered from inside a
                       -- migration: it holds a lock on it for the whole run, so
                       -- the ALTER would wait on a lock only released when the
                       -- run ends. RlsHistorialFlyway enables it at
                       -- startup instead (RlsHistorialFlywayIT checks it).
                       AND c.relname <> 'flyway_schema_history'
                     ORDER BY c.relname
                    """.trimIndent()
                )
                while (rs.next()) sinRls += rs.getString("relname")
            }
        }

        assertTrue(
            sinRls.isEmpty(),
            "Supabase's data API publishes the public schema, so a table without " +
                "RLS is readable from the internet with the anon key - which is " +
                "public by design. Offenders: $sinRls"
        )
    }

    /**
     * The whole point of this test living in `app`: it must see tables from more
     * than one module, or it is just a duplicate of a per-module one.
     */
    @Test
    fun `the schema really spans both feature modules`() {
        val presentes = tablasEnPublic()

        assertTrue("materiales" in presentes, "inventory's tables must be here")
        assertTrue("fichajes" in presentes, "timetracking's tables must be here")
    }

    private fun tablasEnPublic(): Set<String> {
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
        return presentes
    }
}
