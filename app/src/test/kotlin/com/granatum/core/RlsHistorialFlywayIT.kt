package com.granatum.core

import com.granatum.core.infrastructure.RlsHistorialFlyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Closes constitution principle VII's last exception: Flyway's own table gets
 * RLS at startup, with no manual step per environment.
 */
@SpringBootTest
class RlsHistorialFlywayIT {

    @Autowired lateinit var rls: RlsHistorialFlyway
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun activo(): Boolean = jdbc.queryForObject(
        "SELECT relrowsecurity FROM pg_class WHERE relname = 'flyway_schema_history'",
        Boolean::class.java
    )!!

    @Test
    fun `after startup the migration history has row level security`() {
        assertTrue(activo(), "the runner enables it once Flyway has released its lock")
    }

    @Test
    fun `it is enabled again if it was off, and running twice is harmless`() {
        jdbc.execute("ALTER TABLE flyway_schema_history DISABLE ROW LEVEL SECURITY")
        try {
            assertTrue(rls.asegurar())
            assertTrue(activo())
            assertTrue(rls.asegurar())
        } finally {
            jdbc.execute("ALTER TABLE flyway_schema_history ENABLE ROW LEVEL SECURITY")
        }
        assertEquals(true, activo())
    }
}
