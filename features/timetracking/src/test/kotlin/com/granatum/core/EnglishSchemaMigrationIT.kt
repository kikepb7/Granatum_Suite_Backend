package com.granatum.core

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V29 renames the module's schema to English **with data already in it**.
 *
 * Every other test starts from an empty database, where a rename that forgets a
 * stored value, a CHECK or a partial index predicate still passes. Here the
 * schema is built up to V16 with Flyway's own API, filled with rows written the
 * old way, and only then taken to the latest version: what comes out must be
 * the same facts in the new vocabulary.
 */
@Testcontainers
class EnglishSchemaMigrationIT {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    private fun flyway(target: String? = null) = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .apply { if (target != null) target(target) }
        .load()

    private fun <T> connection(block: (Connection) -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use(block)

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.single(sql: String): String? =
        createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getString(1) } }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private val employee = UUID.randomUUID()
    private val closedShift = UUID.randomUUID()
    private val openShift = UUID.randomUUID()
    private val clockInEvent = UUID.randomUUID()
    private val clientEventId = UUID.randomUUID()
    private val unmatchedEvent = UUID.randomUUID()
    private val occurredAt = Instant.parse("2026-10-05T05:00:00.123Z")

    private fun seedTheOldSchema() = connection { c ->
        c.exec(
            """
            INSERT INTO empleados (id, nombre, documento_identidad, puesto, tipo_contrato,
                                   fecha_alta, activo, created_at, updated_at)
            VALUES ('$employee', 'Ana', '12345678Z', 'Florista', 'PARCIAL', '2026-01-01', TRUE, now(), now())
            """
        )
        c.exec(
            """
            INSERT INTO fichajes (id, empleado_id, entrada, salida, estado, minutos_trabajados,
                                  fue_incompleto, ubicacion_entrada_latitud, ubicacion_entrada_longitud,
                                  created_at, updated_at)
            VALUES ('$closedShift', '$employee', '2026-10-05T05:00:00Z', '2026-10-05T13:00:00Z', 'CERRADO',
                    450, FALSE, 37.389100, -5.984500, now(), now()),
                   ('$openShift', '$employee', '2026-10-06T05:00:00Z', NULL, 'EN_CURSO',
                    NULL, FALSE, NULL, NULL, now(), now())
            """
        )
        c.exec(
            """
            INSERT INTO pausas (id, fichaje_id, tipo, inicio, fin)
            VALUES ('${UUID.randomUUID()}', '$closedShift', 'COMIDA', '2026-10-05T09:00:00Z', '2026-10-05T09:30:00Z')
            """
        )
        c.exec(
            """
            INSERT INTO solicitudes_correccion_fichaje (id, fichaje_id, solicitante_id, motivo,
                        valores_propuestos, valores_originales, estado, resuelta_por_id, resuelta_en, created_at)
            VALUES ('${UUID.randomUUID()}', '$closedShift', '$employee', 'Olvidé la pausa del café',
                    '{"entrada":"2026-10-05T05:00:00Z","salida":"2026-10-05T13:00:00Z",
                      "pausas":[{"tipo":"COMIDA","inicio":"2026-10-05T09:00:00Z","fin":"2026-10-05T09:30:00Z"}]}',
                    '{"entrada":"2026-10-05T05:00:00Z","salida":"2026-10-05T13:00:00Z","pausas":[]}',
                    'APROBADA', '${UUID.randomUUID()}', now(), now())
            """
        )
        val oldFingerprint = sha256("$clientEventId|ENTRADA|$occurredAt")
        c.exec(
            """
            INSERT INTO fichaje_eventos (id, client_event_id, empleado_id, fichaje_id, tipo_operacion,
                        occurred_at, received_at, huella_peticion, estado_respuesta, cuerpo_respuesta)
            VALUES ('$clockInEvent', '$clientEventId', '$employee', '$closedShift', 'ENTRADA',
                    '$occurredAt', now(), '$oldFingerprint', 201,
                    '{"id":"$closedShift","empleadoId":"$employee","entrada":"2026-10-05T05:00:00Z",
                      "salida":null,"estado":"EN_CURSO","minutosTrabajados":null,"fueIncompleto":false,
                      "ubicacionEntrada":{"latitud":37.3891,"longitud":-5.9845,"precisionMetros":12},
                      "ubicacionSalida":null,"pausas":[]}'),
                   ('$unmatchedEvent', '${UUID.randomUUID()}', '$employee', '$closedShift', 'SALIDA',
                    '2026-10-05T13:00:00Z', now(), 'not-the-formula', 200, '{}')
            """
        )
        c.exec(
            """
            INSERT INTO exportaciones (id, solicitante_id, rol_solicitante, alcance, empleado_id,
                        desde, hasta, generada_en, completada, filas, huella)
            VALUES ('${UUID.randomUUID()}', '$employee', 'ENCARGADO', 'PLANTILLA', NULL,
                    '2026-10-01', '2026-10-31', now(), TRUE, 2, '${"a".repeat(64)}')
            """
        )
        c.exec(
            """
            INSERT INTO depuraciones_retencion (id, ejecutada_en, fecha_corte, fichajes_eliminados,
                        pausas_eliminadas, eventos_eliminados, solicitudes_eliminadas, exportaciones_eliminadas)
            VALUES ('${UUID.randomUUID()}', now(), '2022-10-01', 1, 2, 3, 4, 5)
            """
        )
    }

    @Test
    fun `existing rows come out in the new vocabulary with nothing lost`() {
        flyway(target = "16").migrate()
        seedTheOldSchema()

        flyway().migrate()

        connection { c ->
            assertEquals("PART_TIME", c.single("SELECT contract_type FROM employees WHERE id = '$employee'"))
            assertEquals("Ana", c.single("SELECT name FROM employees WHERE id = '$employee'"))

            assertEquals("CLOSED", c.single("SELECT status FROM shifts WHERE id = '$closedShift'"))
            assertEquals("IN_PROGRESS", c.single("SELECT status FROM shifts WHERE id = '$openShift'"))
            assertEquals("37.389100", c.single("SELECT clock_in_latitude FROM shifts WHERE id = '$closedShift'"))
            assertEquals("MEAL", c.single("SELECT type FROM shift_breaks WHERE shift_id = '$closedShift'"))

            assertEquals("APPROVED", c.single("SELECT status FROM shift_correction_requests"))
            assertEquals(
                """{"end": "2026-10-05T09:30:00Z", "type": "MEAL", "start": "2026-10-05T09:00:00Z"}""",
                c.single("SELECT proposed_values -> 'breaks' -> 0 FROM shift_correction_requests")
            )
            assertEquals("2026-10-05T13:00:00Z", c.single("SELECT proposed_values ->> 'clockOut' FROM shift_correction_requests"))
            assertEquals("[]", c.single("SELECT original_values ->> 'breaks' FROM shift_correction_requests"))

            assertEquals("CLOCK_IN", c.single("SELECT operation_type FROM shift_events WHERE id = '$clockInEvent'"))
            assertEquals(
                sha256("$clientEventId|CLOCK_IN|$occurredAt"),
                c.single("SELECT request_fingerprint FROM shift_events WHERE id = '$clockInEvent'"),
                "a resend after the deployment must still match the stored fingerprint"
            )
            assertEquals(
                "not-the-formula",
                c.single("SELECT request_fingerprint FROM shift_events WHERE id = '$unmatchedEvent'"),
                "a fingerprint that is not the known formula is left alone"
            )
            assertEquals("CLOCK_OUT", c.single("SELECT operation_type FROM shift_events WHERE id = '$unmatchedEvent'"))
            assertEquals(
                "IN_PROGRESS|$employee|12|t",
                c.single(
                    """
                    SELECT concat_ws('|', response_body ->> 'status', response_body ->> 'employeeId',
                                     response_body -> 'clockInLocation' ->> 'accuracyMeters',
                                     response_body ? 'wasIncomplete')
                      FROM shift_events WHERE id = '$clockInEvent'
                    """
                )
            )

            assertEquals("MANAGER|STAFF", c.single("SELECT requester_role || '|' || scope FROM exports"))
            assertEquals("1|5", c.single("SELECT deleted_shifts || '|' || deleted_exports FROM retention_purges"))

            val withRls = c.single(
                """
                SELECT count(*) FROM pg_class
                 WHERE relname IN ('employees', 'shifts', 'shift_breaks', 'shift_correction_requests',
                                   'shift_events', 'retention_purges', 'exports')
                   AND relrowsecurity
                """
            )
            assertEquals("7", withRls, "RLS survives the renames on every table")

            // The CHECKs and the partial index speak the new codes.
            assertFailsWith<SQLException> {
                c.exec("UPDATE shifts SET status = 'CERRADO' WHERE id = '$closedShift'")
            }
            val failure = assertFailsWith<SQLException> {
                c.exec(
                    """
                    INSERT INTO shifts (id, employee_id, clock_in, status, was_incomplete, created_at, updated_at)
                    VALUES ('${UUID.randomUUID()}', '$employee', now(), 'IN_PROGRESS', FALSE, now(), now())
                    """
                )
            }
            assertTrue(failure.message!!.contains("uk_shifts_employee_in_progress"), failure.message)

            assertEquals(
                "0",
                c.single("SELECT count(*) FROM pg_proc WHERE proname LIKE 'v29_%'"),
                "the migration's helper functions are dropped"
            )
        }
    }
}
