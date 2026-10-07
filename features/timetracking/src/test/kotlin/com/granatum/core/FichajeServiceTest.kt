package com.granatum.core

import com.granatum.core.domain.exception.EmpleadoInactivoException
import com.granatum.core.domain.exception.EmpleadoNotFoundException
import com.granatum.core.domain.exception.FichajeNoEnCursoException
import com.granatum.core.domain.exception.FichajeYaEnCursoException
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.PausaAbiertaAlCerrarException
import com.granatum.core.domain.exception.PausaNoAbiertaException
import com.granatum.core.domain.exception.PausaYaAbiertaException
import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.domain.type.TipoPausa
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.entities.FichajeEntity
import com.granatum.core.infrastructure.database.entities.PausaEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.infrastructure.database.repositories.FichajeRepository
import com.granatum.core.infrastructure.database.repositories.PausaRepository
import com.granatum.core.infrastructure.database.repositories.SolicitudCorreccionFichajeRepository
import com.granatum.core.service.FichajeService
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests of the service's branching, with MockK and no database.
 *
 * These rules are also exercised by `FichajeLifecycleIT` against a real
 * Postgres, which is the stronger check. They are worth having separately for
 * what the integration test cannot give: they run in milliseconds, they isolate
 * the decision from the schema, and a failure here points at the branch rather
 * than at a stack of framework frames. Principle V asks for both levels, and
 * this is the level that fails fast.
 */
class FichajeServiceTest {

    private val fichajeRepository = mockk<FichajeRepository>()
    private val pausaRepository = mockk<PausaRepository>(relaxed = true)
    private val empleadoRepository = mockk<EmpleadoRepository>()
    private val solicitudRepository = mockk<SolicitudCorreccionFichajeRepository>()

    private val service = FichajeService(
        fichajeRepository, pausaRepository, empleadoRepository, solicitudRepository
    )

    private val empleadoId = UUID.randomUUID()
    private val ahora: Instant = Instant.parse("2026-10-05T07:00:00Z")

    private fun empleado(activo: Boolean = true) = EmpleadoEntity(
        id = empleadoId,
        nombre = "Persona",
        documentoIdentidad = "12345678Z",
        puesto = "Florista",
        tipoContrato = TipoContrato.JORNADA_COMPLETA,
        fechaAlta = LocalDate.parse("2026-10-01"),
        activo = activo
    )

    private fun otroEmpleado(id: UUID) = EmpleadoEntity(
        id = id,
        nombre = "Otra persona",
        documentoIdentidad = "00000000T",
        puesto = "Montador",
        tipoContrato = TipoContrato.PARCIAL,
        fechaAlta = LocalDate.parse("2026-10-01"),
        activo = true
    )

    private fun fichaje(
        estado: EstadoFichaje = EstadoFichaje.EN_CURSO,
        duenoId: UUID = empleadoId,
        pausas: List<PausaEntity> = emptyList()
    ): FichajeEntity = FichajeEntity(
        empleado = if (duenoId == empleadoId) empleado() else otroEmpleado(duenoId),
        entrada = ahora,
        estado = estado
    ).also { f -> pausas.forEach { f.anadirPausa(it) } }

    // --- FR-002 ------------------------------------------------------------

    @Test
    fun `clocking in is rejected when a shift is already open`() {
        every { empleadoRepository.findById(empleadoId) } returns Optional.of(empleado())
        every {
            fichajeRepository.findByEmpleadoIdAndEstado(empleadoId, EstadoFichaje.EN_CURSO)
        } returns fichaje()

        assertFailsWith<FichajeYaEnCursoException> {
            service.registrarEntrada(empleadoId, ahora, null)
        }
    }

    // --- FR-010 ------------------------------------------------------------

    @Test
    fun `clocking in is rejected for an inactive employee`() {
        every { empleadoRepository.findById(empleadoId) } returns
            Optional.of(empleado(activo = false))

        assertFailsWith<EmpleadoInactivoException> {
            service.registrarEntrada(empleadoId, ahora, null)
        }
    }

    @Test
    fun `clocking in is rejected for an unknown employee`() {
        every { empleadoRepository.findById(empleadoId) } returns Optional.empty()

        assertFailsWith<EmpleadoNotFoundException> {
            service.registrarEntrada(empleadoId, ahora, null)
        }
    }

    // --- FR-004 ------------------------------------------------------------

    @Test
    fun `starting a break is rejected when one is already open`() {
        val abierta = PausaEntity(tipo = TipoPausa.DESCANSO, inicio = ahora)
        val f = fichaje(pausas = listOf(abierta))
        every { fichajeRepository.findById(f.id) } returns Optional.of(f)

        assertFailsWith<PausaYaAbiertaException> {
            service.iniciarPausa(f.id, empleadoId, ahora.plusSeconds(60), TipoPausa.COMIDA)
        }
    }

    @Test
    fun `ending a break is rejected when none is open`() {
        val f = fichaje()
        every { fichajeRepository.findById(f.id) } returns Optional.of(f)

        assertFailsWith<PausaNoAbiertaException> {
            service.finalizarPausa(f.id, empleadoId, ahora.plusSeconds(60))
        }
    }

    // --- FR-006 ------------------------------------------------------------

    @Test
    fun `closing is rejected while a break is open`() {
        val abierta = PausaEntity(tipo = TipoPausa.DESCANSO, inicio = ahora.plusSeconds(60))
        val f = fichaje(pausas = listOf(abierta))
        every { fichajeRepository.findById(f.id) } returns Optional.of(f)

        assertFailsWith<PausaAbiertaAlCerrarException> {
            service.registrarSalida(f.id, empleadoId, ahora.plusSeconds(3600), null)
        }
    }

    // --- The state guard ---------------------------------------------------

    @Test
    fun `operations on a closed shift are rejected`() {
        val cerrado = fichaje(estado = EstadoFichaje.CERRADO)
        every { fichajeRepository.findById(cerrado.id) } returns Optional.of(cerrado)

        assertFailsWith<FichajeNoEnCursoException> {
            service.iniciarPausa(cerrado.id, empleadoId, ahora, TipoPausa.COMIDA)
        }
        assertFailsWith<FichajeNoEnCursoException> {
            service.finalizarPausa(cerrado.id, empleadoId, ahora)
        }
        assertFailsWith<FichajeNoEnCursoException> {
            service.registrarSalida(cerrado.id, empleadoId, ahora, null)
        }
    }

    /**
     * Ownership is checked against the authenticated subject, so passing
     * someone else's fichaje id does not grant access to it.
     */
    @Test
    fun `operating on another person's shift is rejected`() {
        val ajeno = fichaje(duenoId = UUID.randomUUID())
        every { fichajeRepository.findById(ajeno.id) } returns Optional.of(ajeno)

        assertFailsWith<ForbiddenException> {
            service.iniciarPausa(ajeno.id, empleadoId, ahora, TipoPausa.COMIDA)
        }
    }
}
