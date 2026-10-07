package com.granatum.core

import com.granatum.core.domain.exception.EmpleadoInactivoException
import com.granatum.core.domain.exception.FichajeYaEnCursoException
import com.granatum.core.domain.exception.PausaAbiertaAlCerrarException
import com.granatum.core.domain.exception.PausaYaAbiertaException
import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.domain.type.TipoPausa
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.service.FichajeService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Exercises the whole working-day lifecycle against a real Postgres, running the
 * real Flyway migrations.
 *
 * Testcontainers rather than an in-memory database on purpose: H2 does not
 * reproduce partial unique indexes, RLS, or TIMESTAMPTZ semantics, so it would
 * be testing something else. The partial index is checked here precisely
 * because it is the guarantee a service-side check cannot give.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class FichajeLifecycleIT {

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

    @Autowired lateinit var fichajeService: FichajeService
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var dataSource: DataSource

    private val madrid = com.granatum.core.api.util.RangoFechas.ZONA

    private fun madrid(fecha: String, hora: String) =
        LocalDateTime.of(LocalDate.parse(fecha), LocalTime.parse(hora)).atZone(madrid).toInstant()

    private fun nuevoEmpleado(activo: Boolean = true): EmpleadoEntity =
        empleadoRepository.save(
            EmpleadoEntity(
                nombre = "Persona de prueba",
                // Distinct per run so the unique index does not collide across tests.
                documentoIdentidad = "T${UUID.randomUUID().toString().take(8).uppercase()}",
                puesto = "Florista",
                tipoContrato = TipoContrato.JORNADA_COMPLETA,
                fechaAlta = LocalDate.parse("2026-10-01"),
                activo = activo
            )
        )

    /** SC-001: the criterion the whole feature exists to satisfy. */
    @Test
    fun `a full day with two breaks yields the worked minutes`() {
        val empleado = nuevoEmpleado()

        var fichaje = fichajeService.registrarEntrada(
            empleadoId = empleado.id,
            occurredAt = madrid("2026-10-05", "07:00"),
            ubicacion = null
        )
        assertEquals(EstadoFichaje.EN_CURSO, fichaje.estado)
        assertNull(fichaje.minutosTrabajados, "worked minutes are only known at closing time")

        fichaje = fichajeService.iniciarPausa(
            fichaje.id, empleado.id, madrid("2026-10-05", "09:00"), TipoPausa.DESCANSO
        )
        fichaje = fichajeService.finalizarPausa(
            fichaje.id, empleado.id, madrid("2026-10-05", "09:15")
        )
        fichaje = fichajeService.iniciarPausa(
            fichaje.id, empleado.id, madrid("2026-10-05", "11:00"), TipoPausa.COMIDA
        )
        fichaje = fichajeService.finalizarPausa(
            fichaje.id, empleado.id, madrid("2026-10-05", "11:45")
        )

        fichaje = fichajeService.registrarSalida(
            fichaje.id, empleado.id, madrid("2026-10-05", "16:00"), null
        )

        assertEquals(EstadoFichaje.CERRADO, fichaje.estado)
        assertEquals(480, fichaje.minutosTrabajados, "9h shift minus 1h of breaks")
        assertEquals(2, fichaje.pausas.size)
        assertEquals(false, fichaje.fueIncompleto)
    }

    @Test
    fun `a day without breaks counts the whole interval`() {
        val empleado = nuevoEmpleado()
        var fichaje = fichajeService.registrarEntrada(
            empleado.id, madrid("2026-10-05", "07:00"), null
        )
        fichaje = fichajeService.registrarSalida(
            fichaje.id, empleado.id, madrid("2026-10-05", "15:00"), null
        )
        assertEquals(480, fichaje.minutosTrabajados)
    }

    /** SC-006 against real TIMESTAMPTZ arithmetic, not just the pure calculator. */
    @Test
    fun `a day across the autumn clock change is stored and computed as nine hours`() {
        val empleado = nuevoEmpleado()
        var fichaje = fichajeService.registrarEntrada(
            empleado.id, madrid("2026-10-25", "00:00"), null
        )
        fichaje = fichajeService.registrarSalida(
            fichaje.id, empleado.id, madrid("2026-10-25", "08:00"), null
        )
        assertEquals(
            540,
            fichaje.minutosTrabajados,
            "the night the clocks go back has 25 hours; 480 here would mean the " +
                "instant was not preserved through the database"
        )
    }

    @Test
    fun `a day crossing midnight is a single shift`() {
        val empleado = nuevoEmpleado()
        var fichaje = fichajeService.registrarEntrada(
            empleado.id, madrid("2026-10-07", "22:00"), null
        )
        fichaje = fichajeService.registrarSalida(
            fichaje.id, empleado.id, madrid("2026-10-08", "06:00"), null
        )
        assertEquals(480, fichaje.minutosTrabajados)
    }

    @Test
    fun `a second clock-in is rejected while one is open`() {
        val empleado = nuevoEmpleado()
        fichajeService.registrarEntrada(empleado.id, madrid("2026-10-05", "07:00"), null)

        assertFailsWith<FichajeYaEnCursoException> {
            fichajeService.registrarEntrada(empleado.id, madrid("2026-10-05", "07:05"), null)
        }
    }

    @Test
    fun `an inactive employee cannot clock in`() {
        val empleado = nuevoEmpleado(activo = false)

        assertFailsWith<EmpleadoInactivoException> {
            fichajeService.registrarEntrada(empleado.id, madrid("2026-10-05", "07:00"), null)
        }
    }

    @Test
    fun `a second break is rejected while one is open`() {
        val empleado = nuevoEmpleado()
        val fichaje = fichajeService.registrarEntrada(
            empleado.id, madrid("2026-10-05", "07:00"), null
        )
        fichajeService.iniciarPausa(
            fichaje.id, empleado.id, madrid("2026-10-05", "09:00"), TipoPausa.DESCANSO
        )

        assertFailsWith<PausaYaAbiertaException> {
            fichajeService.iniciarPausa(
                fichaje.id, empleado.id, madrid("2026-10-05", "09:30"), TipoPausa.COMIDA
            )
        }
    }

    @Test
    fun `closing is rejected while a break is still open`() {
        val empleado = nuevoEmpleado()
        val fichaje = fichajeService.registrarEntrada(
            empleado.id, madrid("2026-10-05", "07:00"), null
        )
        fichajeService.iniciarPausa(
            fichaje.id, empleado.id, madrid("2026-10-05", "09:00"), TipoPausa.DESCANSO
        )

        assertFailsWith<PausaAbiertaAlCerrarException> {
            fichajeService.registrarSalida(
                fichaje.id, empleado.id, madrid("2026-10-05", "16:00"), null
            )
        }
    }

    /**
     * The partial unique index, not the service check, is what makes FR-002 hold
     * under concurrency: two simultaneous clock-ins both pass the service's read
     * because each reads before either writes.
     *
     * Asserted by writing directly through JDBC, bypassing the service
     * altogether - the point is that the database refuses even when nothing in
     * the application is looking.
     */
    @Test
    fun `the database itself refuses a second open shift for the same employee`() {
        val empleado = nuevoEmpleado()
        fichajeService.registrarEntrada(empleado.id, madrid("2026-10-05", "07:00"), null)

        val fallo = assertFailsWith<java.sql.SQLException> {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO fichajes
                            (id, empleado_id, entrada, estado, fue_incompleto,
                             created_at, updated_at)
                        VALUES ('${UUID.randomUUID()}', '${empleado.id}', now(),
                                'EN_CURSO', FALSE, now(), now())
                        """.trimIndent()
                    )
                }
            }
        }

        assertEquals(
            true,
            fallo.message!!.contains("uk_fichajes_empleado_en_curso"),
            "the rejection must come from the partial unique index: ${fallo.message}"
        )
    }
}
