package com.granatum.core.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Enables Row Level Security on Flyway's own table once the migrations have
 * run - the one table principle VII could not cover from a migration.
 *
 * Inside a migration the `ALTER TABLE` deadlocks: Flyway holds a lock on
 * `flyway_schema_history` for the whole run. An `ApplicationRunner` runs after
 * the context is up, when Flyway has finished and released it. Until now this
 * was a manual step per Supabase environment (constitution, declared exception
 * nº 1), which is exactly the kind of step someone forgets: PostgREST would
 * serve the migration history to anyone holding the anon key.
 *
 * Idempotent, and it never stops the application: if the connection is not the
 * table's owner (FLYWAY_DB_USERNAME set to another role) the ALTER fails, and
 * the warning says what to run by hand.
 */
@Component
class RlsHistorialFlyway(private val jdbc: JdbcTemplate) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        asegurar()
    }

    /** Returns whether RLS ended up enabled (false if the table does not exist or it could not be enabled). */
    fun asegurar(): Boolean = try {
        val activo = jdbc.queryForList(
            """
            SELECT c.relrowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE c.relname = 'flyway_schema_history' AND n.nspname = current_schema()
            """.trimIndent(),
            Boolean::class.java
        ).firstOrNull()
        when (activo) {
            null -> false
            true -> true
            false -> {
                jdbc.execute("ALTER TABLE flyway_schema_history ENABLE ROW LEVEL SECURITY")
                log.info("Row Level Security activado en flyway_schema_history")
                true
            }
        }
    } catch (e: DataAccessException) {
        log.warn(
            "No se pudo activar RLS en flyway_schema_history ({}). Ejecútalo a mano como propietario: " +
                "ALTER TABLE flyway_schema_history ENABLE ROW LEVEL SECURITY;",
            e.javaClass.simpleName
        )
        false
    }
}
