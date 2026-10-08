package com.granatum.core

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Constitution principle VII for the absence tables (feature 007): who is off,
 * when, and whether on sick leave. Asserted by name so it cannot pass over an
 * empty schema.
 */
@Testcontainers
@SpringBootTest(classes = [AbsencesTestApplication::class])
class RowLevelSecurityIT : BaseAusenciasIT() {

    @Autowired lateinit var dataSource: DataSource

    private val tablasEsperadas = setOf("ausencias", "derechos_vacaciones")

    private fun tablas(condicion: String): Set<String> =
        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                val rs = s.executeQuery(
                    """
                    SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = 'public' AND c.relkind = 'r' AND $condicion
                    """.trimIndent()
                )
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }

    @Test
    fun `the module's migrations created every expected table`() {
        assertEquals(emptySet(), tablasEsperadas - tablas("true"))
    }

    @Test
    fun `every table has row level security enabled`() {
        val sinRls = tablas("NOT c.relrowsecurity AND c.relname <> 'flyway_schema_history'")
        assertTrue(sinRls.isEmpty(), "Tables without RLS, which Supabase's data API would expose: $sinRls")
    }
}
