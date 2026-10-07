package com.granatum.core

import com.granatum.core.api.mappers.toDto
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.model.ValoresFichaje
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.service.CorreccionService
import com.granatum.core.service.FichajeService
import com.granatum.core.service.UbicacionInput
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * SC-011: the REPRESENTANTE role reads the whole workforce's register, sees no
 * locations, and writes nothing.
 *
 * The role exists because article 34.9 names worker representatives as
 * recipients of the register. Its scope is the narrowest that satisfies that,
 * and this test is what keeps it narrow.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class RepresentanteSoloLecturaIT {

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

    private val madrid = com.granatum.core.api.util.RangoFechas.ZONA
    private val representante = UUID.randomUUID()

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

    private val sevilla = UbicacionInput(
        latitud = BigDecimal("37.389100"),
        longitud = BigDecimal("-5.984500"),
        precisionMetros = 12
    )

    /** With a location recorded at both ends, so there is something to hide. */
    private fun jornadaConUbicacion(empleado: EmpleadoEntity) = run {
        val f = fichajeService.registrarEntrada(
            empleado.id, madrid("2026-10-05", "07:00"), sevilla
        )
        fichajeService.registrarSalida(
            f.id, empleado.id, madrid("2026-10-05", "16:00"), sevilla
        )
    }

    @Test
    fun `a REPRESENTANTE reads any person's register`() {
        val empleado = nuevoEmpleado()
        jornadaConUbicacion(empleado)

        val resultado = fichajeService.findPorEmpleadoYRango(
            empleadoIdSolicitado = empleado.id,
            solicitanteId = representante,
            rol = Role.REPRESENTANTE,
            desde = LocalDate.parse("2026-10-01"),
            hasta = LocalDate.parse("2026-10-31")
        )

        assertEquals(1, resultado.size)
        assertEquals(540, resultado.single().minutosTrabajados)
    }

    /**
     * FR-023b. The fields are **dropped**, not nulled - and the difference
     * matters: null already means "recorded without a location", so reusing it
     * here would make the two cases indistinguishable and misrepresent the
     * register.
     *
     * Asserted against the model too, to show the data really is there and is
     * being withheld at the presentation boundary rather than never recorded.
     */
    @Test
    fun `a REPRESENTANTE never sees a location, while a manager does`() {
        val empleado = nuevoEmpleado()
        val fichaje = jornadaConUbicacion(empleado)

        // The location is in the register.
        assertNotNull(fichaje.ubicacionEntrada)
        assertNotNull(fichaje.ubicacionSalida)

        val paraEncargado = fichaje.toDto(incluirUbicacion = true)
        assertNotNull(paraEncargado.ubicacionEntrada, "a manager is shown the location")
        assertNotNull(paraEncargado.ubicacionSalida)

        val paraRepresentante = fichaje.toDto(incluirUbicacion = false)
        assertNull(
            paraRepresentante.ubicacionEntrada,
            "article 34.9 entitles representatives to the register, not to where " +
                "each person was: showing it would process personal data with no " +
                "obligation behind it"
        )
        assertNull(paraRepresentante.ubicacionSalida)

        // Everything else is intact: this is omission of one field, not a
        // degraded view of the record.
        assertEquals(paraEncargado.entrada, paraRepresentante.entrada)
        assertEquals(paraEncargado.salida, paraRepresentante.salida)
        assertEquals(paraEncargado.minutosTrabajados, paraRepresentante.minutosTrabajados)
        assertEquals(paraEncargado.pausas, paraRepresentante.pausas)
    }

    /** FR-023c: every write path refuses, not just some. */
    @Test
    fun `a REPRESENTANTE cannot write anything`() {
        val empleado = nuevoEmpleado()
        val fichaje = jornadaConUbicacion(empleado)

        // Cannot request a correction.
        assertFailsWith<ForbiddenException> {
            correccionService.solicitar(
                fichaje.id, representante, Role.REPRESENTANTE, "No deberia poder",
                ValoresFichaje(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "15:00"), emptyList())
            )
        }

        // Nor resolve one filed by someone else.
        val solicitud = correccionService.solicitar(
            fichaje.id, empleado.id, Role.EMPLEADO, "Correccion legitima",
            ValoresFichaje(madrid("2026-10-05", "07:00"), madrid("2026-10-05", "15:00"), emptyList())
        )
        assertFailsWith<ForbiddenException> {
            correccionService.aprobar(solicitud.id, representante, Role.REPRESENTANTE)
        }
        assertFailsWith<ForbiddenException> {
            correccionService.rechazar(
                solicitud.id, representante, Role.REPRESENTANTE, "No deberia poder rechazar"
            )
        }
    }

    /**
     * A REPRESENTANTE has no fichajes of their own to record: they are not staff
     * for this purpose. Clocking in requires an existing empleado row, and there
     * is none for this subject, so the attempt fails rather than silently
     * creating a shift for a non-employee.
     */
    @Test
    fun `a REPRESENTANTE has no shift to clock`() {
        assertFailsWith<com.granatum.core.domain.exception.EmpleadoNotFoundException> {
            fichajeService.registrarEntrada(representante, madrid("2026-10-05", "07:00"), null)
        }
    }
}
