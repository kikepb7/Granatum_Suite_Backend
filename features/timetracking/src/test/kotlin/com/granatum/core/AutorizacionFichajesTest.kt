package com.granatum.core

import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.ValoresIncoherentesException
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.infrastructure.database.repositories.FichajeRepository
import com.granatum.core.infrastructure.database.repositories.PausaRepository
import com.granatum.core.infrastructure.database.repositories.SolicitudCorreccionFichajeRepository
import com.granatum.core.service.FichajeService
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The visibility boundary, isolated from the database (FR-022, FR-023, SC-004).
 *
 * The case that matters is an EMPLEADO naming someone else's id explicitly:
 * trusting the path id and ignoring the token is the single mistake that would
 * turn this into a data breach, so it is asserted here as well as in the
 * integration test.
 */
class AutorizacionFichajesTest {

    private val fichajeRepository = mockk<FichajeRepository>(relaxed = true)
    private val pausaRepository = mockk<PausaRepository>(relaxed = true)
    private val empleadoRepository = mockk<EmpleadoRepository>()
    private val solicitudRepository = mockk<SolicitudCorreccionFichajeRepository>(relaxed = true)

    private val service = FichajeService(
        fichajeRepository, pausaRepository, empleadoRepository, solicitudRepository
    )

    private val yo = UUID.randomUUID()
    private val otro = UUID.randomUUID()
    private val desde = LocalDate.parse("2026-10-01")
    private val hasta = LocalDate.parse("2026-10-31")

    private fun empleado(id: UUID) = EmpleadoEntity(
        id = id,
        nombre = "Persona",
        documentoIdentidad = "12345678Z",
        puesto = "Florista",
        tipoContrato = TipoContrato.JORNADA_COMPLETA,
        fechaAlta = desde,
        activo = true
    )

    @Test
    fun `an EMPLEADO is refused another person's register even naming their id`() {
        assertFailsWith<ForbiddenException> {
            service.findPorEmpleadoYRango(otro, yo, Role.EMPLEADO, desde, hasta)
        }
    }

    @Test
    fun `an EMPLEADO may read their own`() {
        every { empleadoRepository.findById(yo) } returns Optional.of(empleado(yo))
        every {
            fichajeRepository.findAllByEmpleadoIdAndEntradaBetweenOrderByEntradaDesc(yo, any(), any())
        } returns emptyList()

        service.findPorEmpleadoYRango(yo, yo, Role.EMPLEADO, desde, hasta)
    }

    @Test
    fun `ENCARGADO, ADMIN and REPRESENTANTE may read anyone's`() {
        every { empleadoRepository.findById(otro) } returns Optional.of(empleado(otro))
        every {
            fichajeRepository.findAllByEmpleadoIdAndEntradaBetweenOrderByEntradaDesc(otro, any(), any())
        } returns emptyList()

        listOf(Role.ENCARGADO, Role.ADMIN, Role.REPRESENTANTE).forEach { rol ->
            service.findPorEmpleadoYRango(otro, yo, rol, desde, hasta)
        }
    }

    @Test
    fun `an EMPLEADO cannot list the whole workforce`() {
        assertFailsWith<ForbiddenException> {
            service.findTodosPorRango(Role.EMPLEADO, desde, hasta)
        }
    }

    /**
     * The authorization check runs **before** the range check, so a caller
     * cannot learn whether another person exists by sending a malformed range.
     */
    @Test
    fun `authorization is checked before the range`() {
        assertFailsWith<ForbiddenException> {
            service.findPorEmpleadoYRango(otro, yo, Role.EMPLEADO, hasta, desde)
        }
    }

    @Test
    fun `an inverted range is rejected for a caller who is allowed`() {
        assertFailsWith<ValoresIncoherentesException> {
            service.findPorEmpleadoYRango(yo, yo, Role.EMPLEADO, hasta, desde)
        }
    }
}
