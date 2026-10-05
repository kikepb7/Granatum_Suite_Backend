package com.granatum.core

import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.DepuracionRetencionRepository
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.scheduling.DepuracionRetencionJob
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SC-013: once the four years are fully up the records are purged, and **only**
 * those.
 *
 * Rows are inserted straight through JDBC with the dates the test needs. The
 * service cannot create a shift four years old - the clock validator refuses
 * anything beyond 72 hours, correctly - so going through it would make this
 * test impossible to write rather than more faithful.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class RetencionIT {

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
            registry.add("timetracking.incompletos.cron") { "0 0 4 1 1 *" }
            registry.add("timetracking.retencion.cron") { "0 0 4 1 1 *" }
            // Enabled only here. In a real environment it stays off until the
            // monthly download exists (FR-031d).
            registry.add("timetracking.retencion.habilitada") { "true" }
        }
    }

    @Autowired lateinit var job: DepuracionRetencionJob
    @Autowired lateinit var depuraciones: DepuracionRetencionRepository
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var dataSource: DataSource

    private fun nuevoEmpleado(): EmpleadoEntity =
        empleadoRepository.save(
            EmpleadoEntity(
                nombre = "Persona de prueba",
                documentoIdentidad = "T${UUID.randomUUID().toString().take(8).uppercase()}",
                puesto = "Florista",
                tipoContrato = TipoContrato.JORNADA_COMPLETA,
                fechaAlta = LocalDate.parse("2020-01-01"),
                activo = true
            )
        )

    /** Inserts a closed shift with a break, an event and a correction, at [diasAtras]. */
    private fun fichajeAntiguo(empleado: EmpleadoEntity, diasAtras: Long): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.createStatement().use { s ->
                val entrada = "now() - interval '$diasAtras days'"
                s.execute(
                    """
                    INSERT INTO fichajes
                        (id, empleado_id, entrada, salida, estado, minutos_trabajados,
                         fue_incompleto, created_at, updated_at)
                    VALUES ('$id', '${empleado.id}', $entrada,
                            $entrada + interval '8 hours', 'CERRADO', 480, FALSE,
                            now(), now())
                    """.trimIndent()
                )
                s.execute(
                    """
                    INSERT INTO pausas (id, fichaje_id, tipo, inicio, fin)
                    VALUES ('${UUID.randomUUID()}', '$id', 'COMIDA',
                            $entrada + interval '4 hours', $entrada + interval '4 hours 30 minutes')
                    """.trimIndent()
                )
                s.execute(
                    """
                    INSERT INTO fichaje_eventos
                        (id, client_event_id, empleado_id, fichaje_id, tipo_operacion,
                         occurred_at, received_at, huella_peticion, estado_respuesta,
                         cuerpo_respuesta)
                    VALUES ('${UUID.randomUUID()}', '${UUID.randomUUID()}', '${empleado.id}',
                            '$id', 'ENTRADA', $entrada, $entrada, 'abc', 201, '{}'::jsonb)
                    """.trimIndent()
                )
                s.execute(
                    """
                    INSERT INTO solicitudes_correccion_fichaje
                        (id, fichaje_id, solicitante_id, motivo, valores_propuestos,
                         estado, created_at)
                    VALUES ('${UUID.randomUUID()}', '$id', '${empleado.id}',
                            'Motivo de prueba para retencion', '{}'::jsonb, 'PENDIENTE', now())
                    """.trimIndent()
                )
            }
        }
        return id
    }

    private fun contar(tabla: String, columna: String, id: UUID): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM $tabla WHERE $columna = ?")
                .use { s ->
                    s.setObject(1, id)
                    val rs = s.executeQuery()
                    rs.next()
                    rs.getInt(1)
                }
        }

    @Test
    fun `records past the four years are purged and the run is recorded`() {
        val empleado = nuevoEmpleado()
        // Comfortably past four years (4 years + ~1 month).
        val vencido = fichajeAntiguo(empleado, 1490)

        val registro = job.ejecutar()

        assertEquals(0, contar("fichajes", "id", vencido), "the shift must be gone")
        assertEquals(0, contar("pausas", "fichaje_id", vencido))
        assertEquals(0, contar("fichaje_eventos", "fichaje_id", vencido))
        assertEquals(0, contar("solicitudes_correccion_fichaje", "fichaje_id", vencido))

        assertTrue(registro.fichajesEliminados >= 1, "the audit row must carry the count")
        assertTrue(registro.pausasEliminadas >= 1)
        assertTrue(registro.eventosEliminados >= 1)
        assertTrue(registro.solicitudesEliminadas >= 1)

        val historial = depuraciones.findAllByOrderByEjecutadaEnDesc()
        assertTrue(historial.isNotEmpty(), "the run must leave a recoverable record")
    }

    /**
     * The boundary that matters: "fully elapsed" means exactly that. A record a
     * month short of four years is untouchable, and a purge that took it would
     * be destroying evidence the law still requires.
     */
    @Test
    fun `a record still inside the period is untouched`() {
        val empleado = nuevoEmpleado()
        // Four years minus roughly a month.
        val vigente = fichajeAntiguo(empleado, 1430)

        job.ejecutar()

        assertEquals(1, contar("fichajes", "id", vigente), "a month short of four years survives")
        assertEquals(1, contar("pausas", "fichaje_id", vigente))
        assertEquals(1, contar("fichaje_eventos", "fichaje_id", vigente))
        assertEquals(1, contar("solicitudes_correccion_fichaje", "fichaje_id", vigente))
    }

    @Test
    fun `a run with nothing to purge still records itself with zeroes`() {
        val empleado = nuevoEmpleado()
        fichajeAntiguo(empleado, 10)

        val registro = job.ejecutar()

        assertEquals(0, registro.fichajesEliminados)
        assertEquals(0, registro.pausasEliminadas)
        assertTrue(
            depuraciones.findAllByOrderByEjecutadaEnDesc().isNotEmpty(),
            "an empty run is still evidence the process ran, which is what an " +
                "inspection would ask about"
        )
    }

    /** The audit row holds counts and a date, never personal data. */
    @Test
    fun `the purge record carries no personal data`() {
        val empleado = nuevoEmpleado()
        fichajeAntiguo(empleado, 1490)

        job.ejecutar()

        dataSource.connection.use { connection ->
            connection.createStatement().use { s ->
                val rs = s.executeQuery(
                    """
                    SELECT column_name
                      FROM information_schema.columns
                     WHERE table_name = 'depuraciones_retencion'
                    """.trimIndent()
                )
                val columnas = mutableListOf<String>()
                while (rs.next()) columnas += rs.getString("column_name")

                val sospechosas = columnas.filter { c ->
                    c.contains("empleado") || c.contains("documento") ||
                        c.contains("ubicacion") || c.contains("fichaje_id")
                }
                assertEquals(
                    emptyList(),
                    sospechosas,
                    "this row outlives the retention period it exists to honour, so " +
                        "personal data here would defeat its own purpose: $sospechosas"
                )
            }
        }
    }
}
