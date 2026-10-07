package com.granatum.core

import com.granatum.core.domain.model.ValoresFichaje
import com.granatum.core.domain.model.ValoresPausa
import com.granatum.core.domain.type.EstadoSolicitud
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.domain.type.TipoOperacionFichaje
import com.granatum.core.domain.type.TipoPausa
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.service.CorreccionService
import com.granatum.core.service.FichajeService
import com.granatum.core.service.RegistradorEventos
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The derivability invariant constitution principle III requires since
 * v1.1.0**: a fichaje's state must at all times be derivable from the event log
 * plus its approved corrections.
 *
 * The reason it is worth a test of its own is that it catches a class of bug no
 * other test here can. Every other assertion checks that a specific operation
 * behaves correctly; this one checks that **no value got into the projection by
 * some other route**. If a field in `fichajes` is explained neither by an event
 * nor by an approved correction, it was written through a path that should not
 * exist - and that is exactly what tampering would look like.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class DerivabilidadEstadoIT {

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
        }
    }

    @Autowired lateinit var fichajeService: FichajeService
    @Autowired lateinit var correccionService: CorreccionService
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var registrador: RegistradorEventos
    @Autowired lateinit var dataSource: DataSource

    private val madrid = com.granatum.core.api.util.RangoFechas.ZONA
    private val encargado = UUID.randomUUID()

    private fun madrid(fecha: String, hora: String) =
        LocalDateTime.of(LocalDate.parse(fecha), LocalTime.parse(hora)).atZone(madrid).toInstant()

    private fun nuevoEmpleado(): EmpleadoEntity =
        empleadoRepository.save(
            EmpleadoEntity(
                nombre = "Persona de prueba",
                documentoIdentidad = "T${UUID.randomUUID().toString().take(8).uppercase()}",
                puesto = "Florista",
                tipoContrato = TipoContrato.JORNADA_COMPLETA,
                fechaAlta = LocalDate.parse("2026-10-01"),
                activo = true
            )
        )

    /** Reconstructs the shift's boundaries from the event log alone. */
    private fun derivarDeEventos(fichajeId: UUID): Pair<Instant?, Instant?> {
        var entrada: Instant? = null
        var salida: Instant? = null

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT tipo_operacion, occurred_at
                  FROM fichaje_eventos
                 WHERE fichaje_id = ?
                 ORDER BY occurred_at
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, fichajeId)
                val rs = statement.executeQuery()
                while (rs.next()) {
                    val tipo = TipoOperacionFichaje.valueOf(rs.getString("tipo_operacion"))
                    val cuando = rs.getTimestamp("occurred_at").toInstant()
                    when (tipo) {
                        TipoOperacionFichaje.ENTRADA -> entrada = cuando
                        TipoOperacionFichaje.SALIDA -> salida = cuando
                        else -> Unit
                    }
                }
            }
        }

        return entrada to salida
    }

    private fun registrarEvento(
        empleadoId: UUID,
        fichajeId: UUID,
        tipo: TipoOperacionFichaje,
        occurredAt: Instant
    ) {
        registrador.registrar(
            clientEventId = UUID.randomUUID(),
            empleadoId = empleadoId,
            fichajeId = fichajeId,
            tipoOperacion = tipo,
            occurredAt = occurredAt,
            cuerpoPeticion = """{"occurredAt":"$occurredAt"}""",
            estadoRespuesta = 200,
            cuerpoRespuesta = """{"id":"$fichajeId"}"""
        )
    }

    @Test
    fun `a shift's state is derivable from its events alone`() {
        val empleado = nuevoEmpleado()
        val entrada = madrid("2026-10-05", "07:00")
        val salida = madrid("2026-10-05", "16:00")

        var fichaje = fichajeService.registrarEntrada(empleado.id, entrada, null)
        registrarEvento(empleado.id, fichaje.id, TipoOperacionFichaje.ENTRADA, entrada)

        fichaje = fichajeService.registrarSalida(fichaje.id, empleado.id, salida, null)
        registrarEvento(empleado.id, fichaje.id, TipoOperacionFichaje.SALIDA, salida)

        val (entradaDerivada, salidaDerivada) = derivarDeEventos(fichaje.id)
        val proyeccion = fichajeService.findById(fichaje.id)

        assertEquals(
            entradaDerivada,
            proyeccion.entrada,
            "the projected entry must be explained by an ENTRADA event"
        )
        assertEquals(
            salidaDerivada,
            proyeccion.salida,
            "the projected exit must be explained by a SALIDA event"
        )
    }

    /**
     * After a correction, the projection no longer matches the raw events - and
     * that is correct. What the invariant requires is that the difference is
     * explained by an **approved** correction, and that the pre-correction value
     * is still recoverable from it.
     */
    @Test
    fun `where the projection diverges from the events, an approved correction explains it`() {
        val empleado = nuevoEmpleado()
        val entrada = madrid("2026-10-05", "07:00")
        val salidaFichada = madrid("2026-10-05", "16:00")
        val salidaCorregida = madrid("2026-10-05", "15:00")

        var fichaje = fichajeService.registrarEntrada(empleado.id, entrada, null)
        registrarEvento(empleado.id, fichaje.id, TipoOperacionFichaje.ENTRADA, entrada)
        fichaje = fichajeService.registrarSalida(fichaje.id, empleado.id, salidaFichada, null)
        registrarEvento(empleado.id, fichaje.id, TipoOperacionFichaje.SALIDA, salidaFichada)

        val solicitud = correccionService.solicitar(
            fichaje.id, empleado.id, Role.EMPLEADO, "Me marche una hora antes",
            ValoresFichaje(entrada, salidaCorregida, emptyList())
        )
        correccionService.aprobar(solicitud.id, encargado, Role.ENCARGADO)

        val proyeccion = fichajeService.findById(fichaje.id)
        val (_, salidaDerivada) = derivarDeEventos(fichaje.id)

        // The projection diverges from the log...
        assertEquals(salidaCorregida, proyeccion.salida)
        assertEquals(salidaFichada, salidaDerivada)
        assertTrue(proyeccion.salida != salidaDerivada)

        // ...and an approved correction accounts for the difference, carrying
        // the value the events recorded.
        val correcciones = correccionService.findByFichaje(fichaje.id)
        val aprobadas = correcciones.filter { it.estado == EstadoSolicitud.APROBADA }
        assertEquals(1, aprobadas.size, "exactly one approved correction must explain the divergence")

        val originales = aprobadas.single().valoresOriginales
        assertEquals(
            salidaDerivada,
            originales?.salida,
            "the correction's snapshot must hold the value the event log recorded, " +
                "which is what keeps the chain from events to current state unbroken"
        )
    }

    /**
     * A tampered projection is detectable.
     *
     * Writes directly to the table - the one route the application does not
     * offer - and shows the invariant notices. This is the failure mode the
     * invariant exists for: not a bug in an operation, but a value that no
     * operation explains.
     */
    @Test
    fun `a value written outside the event log is detectable as unexplained`() {
        val empleado = nuevoEmpleado()
        val entrada = madrid("2026-10-05", "07:00")
        val salida = madrid("2026-10-05", "16:00")

        var fichaje = fichajeService.registrarEntrada(empleado.id, entrada, null)
        registrarEvento(empleado.id, fichaje.id, TipoOperacionFichaje.ENTRADA, entrada)
        fichaje = fichajeService.registrarSalida(fichaje.id, empleado.id, salida, null)
        registrarEvento(empleado.id, fichaje.id, TipoOperacionFichaje.SALIDA, salida)

        // Straight UPDATE, bypassing everything.
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "UPDATE fichajes SET salida = salida + interval '2 hours' " +
                        "WHERE id = '${fichaje.id}'"
                )
            }
        }

        val proyeccion = fichajeService.findById(fichaje.id)
        val (_, salidaDerivada) = derivarDeEventos(fichaje.id)
        val aprobadas = correccionService.findByFichaje(fichaje.id)
            .filter { it.estado == EstadoSolicitud.APROBADA }

        assertTrue(
            proyeccion.salida != salidaDerivada && aprobadas.isEmpty(),
            "the projection diverges from the event log with no approved correction " +
                "to explain it - which is precisely the signature of tampering, and " +
                "what this invariant is for"
        )
    }

    @Test
    fun `breaks are reflected in the projection and the events agree on their count`() {
        val empleado = nuevoEmpleado()
        val entrada = madrid("2026-10-05", "07:00")

        var fichaje = fichajeService.registrarEntrada(empleado.id, entrada, null)
        registrarEvento(empleado.id, fichaje.id, TipoOperacionFichaje.ENTRADA, entrada)

        val inicioPausa = madrid("2026-10-05", "11:00")
        fichaje = fichajeService.iniciarPausa(fichaje.id, empleado.id, inicioPausa, TipoPausa.COMIDA)
        registrarEvento(empleado.id, fichaje.id, TipoOperacionFichaje.INICIO_PAUSA, inicioPausa)

        val finPausa = madrid("2026-10-05", "11:45")
        fichaje = fichajeService.finalizarPausa(fichaje.id, empleado.id, finPausa)
        registrarEvento(empleado.id, fichaje.id, TipoOperacionFichaje.FIN_PAUSA, finPausa)

        val salida = madrid("2026-10-05", "16:00")
        fichaje = fichajeService.registrarSalida(fichaje.id, empleado.id, salida, null)
        registrarEvento(empleado.id, fichaje.id, TipoOperacionFichaje.SALIDA, salida)

        val proyeccion = fichajeService.findById(fichaje.id)
        assertEquals(1, proyeccion.pausas.size)
        assertEquals(inicioPausa, proyeccion.pausas.single().inicio)
        assertEquals(finPausa, proyeccion.pausas.single().fin)
        assertEquals(495, proyeccion.minutosTrabajados)

        // One INICIO_PAUSA and one FIN_PAUSA in the log account for the single
        // break in the projection.
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM fichaje_eventos WHERE fichaje_id = ? " +
                    "AND tipo_operacion IN ('INICIO_PAUSA','FIN_PAUSA')"
            ).use { statement ->
                statement.setObject(1, fichaje.id)
                val rs = statement.executeQuery()
                rs.next()
                assertEquals(2, rs.getInt(1))
            }
        }
    }
}
