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
import com.granatum.core.infrastructure.database.ValoresFichajeJson
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.entities.FichajeEntity
import com.granatum.core.infrastructure.database.entities.SolicitudCorreccionFichajeEntity
import com.granatum.core.infrastructure.database.repositories.FichajeRepository
import com.granatum.core.infrastructure.database.repositories.SolicitudCorreccionFichajeRepository
import com.granatum.core.service.CorreccionService
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The correction service's refusal branches, without a database.
 *
 * `CorreccionFlujoIT` covers the same rules against a real Postgres, which is
 * the stronger check. These run in milliseconds and point at the branch rather
 * than at a stack of framework frames, which is what makes them worth keeping
 * alongside it.
 */
class CorreccionServiceTest {

    private val solicitudRepository = mockk<SolicitudCorreccionFichajeRepository>(relaxed = true)
    private val fichajeRepository = mockk<FichajeRepository>(relaxed = true)
    private val json = ValoresFichajeJson()

    private val service = CorreccionService(
        solicitudRepository,
        fichajeRepository,
        json,
        Clock.fixed(Instant.parse("2026-10-06T09:00:00Z"), ZoneOffset.UTC)
    )

    private val empleadoId = UUID.randomUUID()
    private val entrada: Instant = Instant.parse("2026-10-05T07:00:00Z")
    private val salida: Instant = Instant.parse("2026-10-05T16:00:00Z")

    private fun empleado(id: UUID = empleadoId) = EmpleadoEntity(
        id = id,
        nombre = "Persona",
        documentoIdentidad = "12345678Z",
        puesto = "Florista",
        tipoContrato = TipoContrato.JORNADA_COMPLETA,
        fechaAlta = LocalDate.parse("2026-10-01"),
        activo = true
    )

    private fun fichaje(estado: EstadoFichaje = EstadoFichaje.CERRADO, duenoId: UUID = empleadoId) =
        FichajeEntity(
            empleado = empleado(duenoId),
            entrada = entrada,
            salida = salida,
            estado = estado,
            minutosTrabajados = 540
        )

    private fun solicitud(
        estado: EstadoSolicitud = EstadoSolicitud.PENDIENTE,
        solicitanteId: UUID = empleadoId
    ) = SolicitudCorreccionFichajeEntity(
        fichaje = fichaje(),
        solicitanteId = solicitanteId,
        motivo = "Motivo suficientemente largo",
        valoresPropuestos = json.escribir(ValoresFichaje(entrada, salida, emptyList())),
        estado = estado
    )

    private val propuestaValida = ValoresFichaje(
        entrada, salida,
        listOf(ValoresPausa(TipoPausa.COMIDA, Instant.parse("2026-10-05T11:00:00Z"), Instant.parse("2026-10-05T11:45:00Z")))
    )

    // --- Requesting ---------------------------------------------------------

    @Test
    fun `an open shift cannot be corrected`() {
        val abierto = fichaje(estado = EstadoFichaje.EN_CURSO)
        every { fichajeRepository.findById(abierto.id) } returns Optional.of(abierto)

        assertFailsWith<FichajeNoFinalizadoException> {
            service.solicitar(abierto.id, empleadoId, Role.EMPLEADO, "Motivo largo", propuestaValida)
        }
    }

    @Test
    fun `an EMPLEADO cannot correct someone else's shift`() {
        val ajeno = fichaje(duenoId = UUID.randomUUID())
        every { fichajeRepository.findById(ajeno.id) } returns Optional.of(ajeno)

        assertFailsWith<ForbiddenException> {
            service.solicitar(ajeno.id, empleadoId, Role.EMPLEADO, "Motivo largo", propuestaValida)
        }
    }

    @Test
    fun `a REPRESENTANTE cannot request a correction`() {
        val f = fichaje()
        every { fichajeRepository.findById(f.id) } returns Optional.of(f)

        assertFailsWith<ForbiddenException> {
            service.solicitar(f.id, UUID.randomUUID(), Role.REPRESENTANTE, "Motivo largo", propuestaValida)
        }
    }

    @Test
    fun `an incoherent proposal is rejected at request time`() {
        val f = fichaje()
        every { fichajeRepository.findById(f.id) } returns Optional.of(f)

        assertFailsWith<ValoresIncoherentesException> {
            service.solicitar(
                f.id, empleadoId, Role.EMPLEADO, "Salida antes de la entrada",
                ValoresFichaje(salida, entrada, emptyList())
            )
        }

        assertFailsWith<ValoresIncoherentesException> {
            service.solicitar(
                f.id, empleadoId, Role.EMPLEADO, "Pausas solapadas",
                ValoresFichaje(
                    entrada, salida,
                    listOf(
                        ValoresPausa(TipoPausa.COMIDA, Instant.parse("2026-10-05T09:00:00Z"), Instant.parse("2026-10-05T10:00:00Z")),
                        ValoresPausa(TipoPausa.OTRO, Instant.parse("2026-10-05T09:30:00Z"), Instant.parse("2026-10-05T10:30:00Z"))
                    )
                )
            )
        }
    }

    // --- Resolving ----------------------------------------------------------

    @Test
    fun `only ENCARGADO and ADMIN can resolve`() {
        val s = solicitud()
        every { solicitudRepository.findById(s.id) } returns Optional.of(s)

        assertFailsWith<ForbiddenException> {
            service.aprobar(s.id, UUID.randomUUID(), Role.EMPLEADO)
        }
        assertFailsWith<ForbiddenException> {
            service.aprobar(s.id, UUID.randomUUID(), Role.REPRESENTANTE)
        }
    }

    /** FR-015a, the rule the original FR-015 missed. */
    @Test
    fun `nobody resolves their own request, not even an ENCARGADO`() {
        val propia = solicitud(solicitanteId = empleadoId)
        every { solicitudRepository.findById(propia.id) } returns Optional.of(propia)

        assertFailsWith<ForbiddenException> {
            service.aprobar(propia.id, empleadoId, Role.ENCARGADO)
        }
    }

    @Test
    fun `an already resolved request cannot be resolved again`() {
        val resuelta = solicitud(estado = EstadoSolicitud.APROBADA)
        every { solicitudRepository.findById(resuelta.id) } returns Optional.of(resuelta)

        assertFailsWith<SolicitudYaResueltaException> {
            service.aprobar(resuelta.id, UUID.randomUUID(), Role.ENCARGADO)
        }
    }

    /**
     * The conditional claim is the real guard, so its zero-rows outcome is
     * asserted directly: a read-then-write cannot hold FR-019 under
     * concurrency, and this is what happens when the other transaction won.
     */
    @Test
    fun `losing the conditional claim is reported as already resolved`() {
        val s = solicitud()
        every { solicitudRepository.findById(s.id) } returns Optional.of(s)
        every {
            solicitudRepository.resolverSiSiguePendiente(any(), any(), any(), any(), any())
        } returns 0

        assertFailsWith<SolicitudYaResueltaException> {
            service.aprobar(s.id, UUID.randomUUID(), Role.ENCARGADO)
        }
    }
}
