package com.granatum.core

import com.granatum.core.domain.exception.FichajeNoFinalizadoException
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.SolicitudYaResueltaException
import com.granatum.core.domain.exception.ValoresIncoherentesException
import com.granatum.core.domain.model.ValoresFichaje
import com.granatum.core.domain.model.ValoresPausa
import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.domain.type.EstadoSolicitud
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.domain.type.TipoPausa
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.infrastructure.database.repositories.FichajeRepository
import com.granatum.core.scheduling.MarcadoFichajesIncompletosJob
import com.granatum.core.service.CorreccionService
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The correction flow: request, approve, reject - and above all that the
 * original survives.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class CorreccionFlujoIT {

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
    @Autowired lateinit var fichajeRepository: FichajeRepository
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var job: MarcadoFichajesIncompletosJob

    private val madrid = com.granatum.core.api.util.RangoFechas.ZONA
    private val encargado = UUID.randomUUID()

    private fun madrid(fecha: String, hora: String) =
        LocalDateTime.of(LocalDate.parse(fecha), LocalTime.parse(hora)).atZone(madrid).toInstant()

    private fun ayer(hora: String) =
        LocalDateTime.of(LocalDate.now(madrid).minusDays(1), LocalTime.parse(hora))
            .atZone(madrid).toInstant()

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

    private fun jornadaCerrada(empleado: EmpleadoEntity) = run {
        val f = fichajeService.registrarEntrada(empleado.id, madrid("2026-10-05", "07:00"), null)
        fichajeService.registrarSalida(f.id, empleado.id, madrid("2026-10-05", "16:00"), null)
    }

    /** SC-003: the value changes, and the original is still there with its authorship. */
    @Test
    fun `an approved correction applies the new values and preserves the originals`() {
        val empleado = nuevoEmpleado()
        val fichaje = jornadaCerrada(empleado)
        assertEquals(540, fichaje.minutosTrabajados)

        val solicitud = correccionService.solicitar(
            fichajeId = fichaje.id,
            solicitanteId = empleado.id,
            rol = Role.EMPLEADO,
            motivo = "Olvide fichar la pausa de comida",
            propuestos = ValoresFichaje(
                entrada = madrid("2026-10-05", "07:00"),
                salida = madrid("2026-10-05", "16:00"),
                pausas = listOf(
                    ValoresPausa(TipoPausa.COMIDA, madrid("2026-10-05", "11:00"), madrid("2026-10-05", "11:45"))
                )
            )
        )
        assertEquals(EstadoSolicitud.PENDIENTE, solicitud.estado)

        val aprobada = correccionService.aprobar(solicitud.id, encargado, Role.ENCARGADO)

        assertEquals(EstadoSolicitud.APROBADA, aprobada.estado)
        assertEquals(encargado, aprobada.resueltaPorId)
        assertNotNull(aprobada.resueltaEn)

        // The original is recoverable: 9h with no breaks.
        val originales = assertNotNull(aprobada.valoresOriginales)
        assertEquals(emptyList(), originales.pausas)
        assertEquals(madrid("2026-10-05", "16:00"), originales.salida)

        // And the fichaje now reflects the correction: 9h minus 45 min.
        //
        // Read through the service rather than the repository: `pausas` is a
        // lazy collection and `open-in-view` is false, so touching it outside a
        // transaction throws. The service maps inside one, which is also the
        // path production uses.
        val recargado = fichajeService.findById(fichaje.id)
        assertEquals(495, recargado.minutosTrabajados)
        assertEquals(1, recargado.pausas.size)
    }

    @Test
    fun `a rejected correction leaves the fichaje untouched`() {
        val empleado = nuevoEmpleado()
        val fichaje = jornadaCerrada(empleado)

        val solicitud = correccionService.solicitar(
            fichaje.id, empleado.id, Role.EMPLEADO, "Me marche antes de lo apuntado",
            ValoresFichaje(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "14:00"), emptyList())
        )

        val rechazada = correccionService.rechazar(
            solicitud.id, encargado, Role.ENCARGADO, "No coincide con el parte de obra"
        )

        assertEquals(EstadoSolicitud.RECHAZADA, rechazada.estado)
        assertNotNull(rechazada.motivoResolucion)
        assertNull(rechazada.valoresOriginales, "nothing was applied, so there is no 'before'")

        val recargado = fichajeRepository.findById(fichaje.id).orElseThrow()
        assertEquals(540, recargado.minutosTrabajados, "the fichaje must be byte-for-byte unchanged")
        assertEquals(madrid("2026-10-05", "16:00"), recargado.salida)
    }

    /** FR-019: both terminal states are final. */
    @Test
    fun `resolving twice is rejected`() {
        val empleado = nuevoEmpleado()
        val fichaje = jornadaCerrada(empleado)
        val solicitud = correccionService.solicitar(
            fichaje.id, empleado.id, Role.EMPLEADO, "Correccion de prueba",
            ValoresFichaje(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "15:00"), emptyList())
        )

        correccionService.aprobar(solicitud.id, encargado, Role.ENCARGADO)

        assertFailsWith<SolicitudYaResueltaException> {
            correccionService.aprobar(solicitud.id, encargado, Role.ENCARGADO)
        }
        assertFailsWith<SolicitudYaResueltaException> {
            correccionService.rechazar(solicitud.id, encargado, Role.ENCARGADO, "Tarde")
        }
    }

    /**
     * FR-015a, the rule that FR-015 as first written missed.
     *
     * An ENCARGADO has their own fichajes, so they could file a correction for
     * their own shift and approve it themselves. That is the self-approval the
     * approval step exists to prevent, and the original requirement only barred
     * it for EMPLEADO - a role that never reaches the resolution path at all.
     */
    @Test
    fun `nobody resolves their own request, not even an ENCARGADO`() {
        val empleadoEncargado = nuevoEmpleado()
        val fichaje = jornadaCerrada(empleadoEncargado)

        val solicitud = correccionService.solicitar(
            fichaje.id, empleadoEncargado.id, Role.ENCARGADO, "Mi propia correccion",
            ValoresFichaje(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "15:00"), emptyList())
        )

        assertFailsWith<ForbiddenException> {
            correccionService.aprobar(solicitud.id, empleadoEncargado.id, Role.ENCARGADO)
        }
    }

    @Test
    fun `an EMPLEADO cannot resolve a correction at all`() {
        val empleado = nuevoEmpleado()
        val fichaje = jornadaCerrada(empleado)
        val solicitud = correccionService.solicitar(
            fichaje.id, empleado.id, Role.EMPLEADO, "Correccion de prueba",
            ValoresFichaje(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "15:00"), emptyList())
        )

        assertFailsWith<ForbiddenException> {
            correccionService.aprobar(solicitud.id, UUID.randomUUID(), Role.EMPLEADO)
        }
    }

    @Test
    fun `a REPRESENTANTE cannot request a correction`() {
        val empleado = nuevoEmpleado()
        val fichaje = jornadaCerrada(empleado)

        assertFailsWith<ForbiddenException> {
            correccionService.solicitar(
                fichaje.id, UUID.randomUUID(), Role.REPRESENTANTE, "No deberia poder",
                ValoresFichaje(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "15:00"), emptyList())
            )
        }
    }

    @Test
    fun `an open shift cannot be corrected`() {
        val empleado = nuevoEmpleado()
        val abierto = fichajeService.registrarEntrada(
            empleado.id, madrid("2026-10-05", "07:00"), null
        )

        assertFailsWith<FichajeNoFinalizadoException> {
            correccionService.solicitar(
                abierto.id, empleado.id, Role.EMPLEADO, "Deberia rechazarse",
                ValoresFichaje(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "15:00"), emptyList())
            )
        }
    }

    @Test
    fun `an incoherent proposal is rejected when requested`() {
        val empleado = nuevoEmpleado()
        val fichaje = jornadaCerrada(empleado)

        assertFailsWith<ValoresIncoherentesException> {
            correccionService.solicitar(
                fichaje.id, empleado.id, Role.EMPLEADO, "Salida antes de la entrada",
                ValoresFichaje(madrid("2026-10-05", "16:00"), madrid("2026-10-05", "07:00"), emptyList())
            )
        }

        assertFailsWith<ValoresIncoherentesException> {
            correccionService.solicitar(
                fichaje.id, empleado.id, Role.EMPLEADO, "Pausas solapadas entre si",
                ValoresFichaje(
                    madrid("2026-10-05", "07:00"), madrid("2026-10-05", "16:00"),
                    listOf(
                        ValoresPausa(TipoPausa.COMIDA, madrid("2026-10-05", "09:00"), madrid("2026-10-05", "10:00")),
                        ValoresPausa(TipoPausa.OTRO, madrid("2026-10-05", "09:30"), madrid("2026-10-05", "10:30"))
                    )
                )
            )
        }
    }

    /** SC-010: a reconstructed day stays distinguishable from one closed at the time. */
    @Test
    fun `completing an INCOMPLETO shift keeps it marked as reconstructed`() {
        val empleado = nuevoEmpleado()
        val olvidado = fichajeService.registrarEntrada(empleado.id, ayer("08:00"), null)

        job.marcarIncompletos()
        assertEquals(
            EstadoFichaje.INCOMPLETO,
            fichajeRepository.findById(olvidado.id).orElseThrow().estado
        )

        val solicitud = correccionService.solicitar(
            olvidado.id, empleado.id, Role.EMPLEADO, "Olvide fichar la salida",
            ValoresFichaje(ayer("08:00"), ayer("16:00"), emptyList())
        )
        correccionService.aprobar(solicitud.id, encargado, Role.ENCARGADO)

        val completado = fichajeRepository.findById(olvidado.id).orElseThrow()
        assertEquals(EstadoFichaje.CERRADO, completado.estado)
        assertEquals(480, completado.minutosTrabajados)
        assertTrue(
            completado.fueIncompleto,
            "the flag must survive completion, or an inspection report could not " +
                "tell a reconstructed day from one closed at the time"
        )
    }

    /** A correction that removes a break must actually delete its row. */
    @Test
    fun `a correction can remove a break that was recorded by mistake`() {
        val empleado = nuevoEmpleado()
        var fichaje = fichajeService.registrarEntrada(
            empleado.id, madrid("2026-10-05", "07:00"), null
        )
        fichaje = fichajeService.iniciarPausa(
            fichaje.id, empleado.id, madrid("2026-10-05", "09:00"), TipoPausa.DESCANSO
        )
        fichaje = fichajeService.finalizarPausa(
            fichaje.id, empleado.id, madrid("2026-10-05", "09:30")
        )
        fichaje = fichajeService.registrarSalida(
            fichaje.id, empleado.id, madrid("2026-10-05", "16:00"), null
        )
        assertEquals(510, fichaje.minutosTrabajados)

        val solicitud = correccionService.solicitar(
            fichaje.id, empleado.id, Role.EMPLEADO, "Esa pausa no existio",
            ValoresFichaje(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "16:00"), emptyList())
        )
        correccionService.aprobar(solicitud.id, encargado, Role.ENCARGADO)

        val recargado = fichajeService.findById(fichaje.id)
        assertEquals(0, recargado.pausas.size, "orphanRemoval must delete the row, not orphan it")
        assertEquals(540, recargado.minutosTrabajados)
    }
}
