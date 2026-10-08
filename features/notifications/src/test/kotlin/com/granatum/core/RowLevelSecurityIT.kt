package com.granatum.core

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Constitution principle VII for the notices table (feature 008). */
@Testcontainers
@SpringBootTest(classes = [NotificationsTestApplication::class])
class RowLevelSecurityIT : BaseNotificacionesIT() {

    @Autowired lateinit var dataSource: DataSource

    private fun tablas(condicion: String): Set<String> =
        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                val rs = s.executeQuery(
                    "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                        "WHERE n.nspname = 'public' AND c.relkind = 'r' AND $condicion"
                )
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }

    @Test
    fun `the migration created the notices table`() {
        assertEquals(emptySet(), setOf("notificaciones") - tablas("true"))
    }

    @Test
    fun `every table has row level security enabled`() {
        val sinRls = tablas("NOT c.relrowsecurity AND c.relname <> 'flyway_schema_history'")
        assertTrue(sinRls.isEmpty(), "Tables without RLS: $sinRls")
    }
}
