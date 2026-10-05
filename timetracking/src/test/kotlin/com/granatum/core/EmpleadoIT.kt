package com.granatum.core

import com.granatum.core.domain.exception.DocumentoDuplicadoException
import com.granatum.core.domain.exception.EmpleadoInactivoException
import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.service.EmpleadoService
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
import kotlin.test.assertTrue

/**
 * Staff management against a real Postgres: engagement, exit, and the history
 * surviving both.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class EmpleadoIT {

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
        }
    }

    @Autowired lateinit var empleadoService: EmpleadoService
    @Autowired lateinit var fichajeService: FichajeService
    @Autowired lateinit var empleadoRepository: EmpleadoRepository

    private val madrid = com.granatum.core.api.util.RangoFechas.ZONA

    private fun madrid(fecha: String, hora: String) =
        LocalDateTime.of(LocalDate.parse(fecha), LocalTime.parse(hora)).atZone(madrid).toInstant()

    /** A distinct valid DNI per call, check letter computed, not invented. */
    private fun dniValido(): String {
        val numero = (10_000_000..99_999_999).random()
        val letra = "TRWAGMYFPDXBNJZSQVHLCKE"[numero % 23]
        return "$numero$letra"
    }

    @Test
    fun `an engagement is registered as active`() {
        val empleado = empleadoService.crear(
            nombre = "Maria Lopez Ruiz",
            documentoIdentidad = dniValido(),
            puesto = "Florista",
            tipoContrato = TipoContrato.JORNADA_COMPLETA,
            fechaAlta = LocalDate.parse("2026-10-01")
        )

        assertTrue(empleado.activo)
        assertEquals("Maria Lopez Ruiz", empleado.nombre)
        assertEquals(TipoContrato.JORNADA_COMPLETA, empleado.tipoContrato)
    }

    /** FR-029, through the unique index and the service check alike. */
    @Test
    fun `a duplicate document is rejected`() {
        val dni = dniValido()
        empleadoService.crear(
            "Primera Persona", dni, "Florista",
            TipoContrato.JORNADA_COMPLETA, LocalDate.parse("2026-10-01")
        )

        assertFailsWith<DocumentoDuplicadoException> {
            empleadoService.crear(
                "Segunda Persona", dni, "Montador",
                TipoContrato.PARCIAL, LocalDate.parse("2026-10-02")
            )
        }
    }

    /**
     * The punctuation case: the same document written differently must still
     * collide. Without normalisation the unique index would accept it and the
     * same person would exist twice.
     */
    @Test
    fun `the same document with different punctuation still collides`() {
        val numero = (10_000_000..99_999_999).random()
        val letra = "TRWAGMYFPDXBNJZSQVHLCKE"[numero % 23]

        empleadoService.crear(
            "Primera Persona", "$numero$letra", "Florista",
            TipoContrato.JORNADA_COMPLETA, LocalDate.parse("2026-10-01")
        )

        assertFailsWith<DocumentoDuplicadoException> {
            empleadoService.crear(
                "Segunda Persona", "$numero-${letra.lowercaseChar()}", "Montador",
                TipoContrato.PARCIAL, LocalDate.parse("2026-10-02")
            )
        }
    }

    @Test
    fun `the stored document is normalised`() {
        val numero = (10_000_000..99_999_999).random()
        val letra = "TRWAGMYFPDXBNJZSQVHLCKE"[numero % 23]

        val empleado = empleadoService.crear(
            "Persona", " $numero-${letra.lowercaseChar()} ", "Florista",
            TipoContrato.JORNADA_COMPLETA, LocalDate.parse("2026-10-01")
        )

        assertEquals("$numero$letra", empleado.documentoIdentidad)
    }

    /** FR-030: the exit is logical, and the history survives it intact. */
    @Test
    fun `deactivating stops clocking in and leaves the history whole`() {
        val empleado = empleadoService.crear(
            "Persona", dniValido(), "Florista",
            TipoContrato.JORNADA_COMPLETA, LocalDate.parse("2026-10-01")
        )

        val f = fichajeService.registrarEntrada(empleado.id, madrid("2026-10-05", "07:00"), null)
        fichajeService.registrarSalida(f.id, empleado.id, madrid("2026-10-05", "16:00"), null)

        empleadoService.cambiarActivo(empleado.id, false)

        assertFailsWith<EmpleadoInactivoException> {
            fichajeService.registrarEntrada(empleado.id, madrid("2026-10-06", "07:00"), null)
        }

        val historial = fichajeService.findPorEmpleadoYRango(
            empleado.id, empleado.id, Role.ADMIN,
            LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")
        )
        assertEquals(1, historial.size, "the history must survive the exit")
        assertEquals(540, historial.single().minutosTrabajados)
    }

    @Test
    fun `reactivating allows clocking in again`() {
        val empleado = empleadoService.crear(
            "Persona", dniValido(), "Florista",
            TipoContrato.JORNADA_COMPLETA, LocalDate.parse("2026-10-01")
        )

        empleadoService.cambiarActivo(empleado.id, false)
        empleadoService.cambiarActivo(empleado.id, true)

        val f = fichajeService.registrarEntrada(empleado.id, madrid("2026-10-06", "07:00"), null)
        assertEquals(EstadoFichaje.EN_CURSO, f.estado)
    }

    /**
     * FR-030, asserted where it cannot be argued with: the repository offers no
     * deletion at all, so there is nothing to remember not to call.
     */
    @Test
    fun `the employee repository exposes no deletion`() {
        val metodos = EmpleadoRepository::class.java.methods.map { it.name }

        assertEquals(
            emptyList(),
            metodos.filter { it.startsWith("delete") || it.startsWith("remove") },
            "removing a person would destroy four years of working-time history"
        )
    }

    @Test
    fun `an update changes the job data but not the document`() {
        val dni = dniValido()
        val empleado = empleadoService.crear(
            "Nombre Original", dni, "Florista",
            TipoContrato.JORNADA_COMPLETA, LocalDate.parse("2026-10-01")
        )

        val actualizado = empleadoService.actualizar(
            id = empleado.id,
            nombre = "Nombre Corregido",
            puesto = "Encargada de taller",
            tipoContrato = TipoContrato.PARCIAL,
            fechaAlta = LocalDate.parse("2026-09-15")
        )

        assertEquals("Nombre Corregido", actualizado.nombre)
        assertEquals("Encargada de taller", actualizado.puesto)
        assertEquals(TipoContrato.PARCIAL, actualizado.tipoContrato)
        assertEquals(dni, actualizado.documentoIdentidad, "the document is untouched by an update")
        assertTrue(actualizado.activo, "nor is the active flag, which has its own endpoint")
    }

    @Test
    fun `listing can be filtered by active status`() {
        val activa = empleadoService.crear(
            "Activa", dniValido(), "Florista",
            TipoContrato.JORNADA_COMPLETA, LocalDate.parse("2026-10-01")
        )
        val inactiva = empleadoService.crear(
            "Inactiva", dniValido(), "Montador",
            TipoContrato.POR_HORAS, LocalDate.parse("2026-10-01")
        )
        empleadoService.cambiarActivo(inactiva.id, false)

        val activos = empleadoService.findAll(activo = true).map { it.id }
        val inactivos = empleadoService.findAll(activo = false).map { it.id }

        assertTrue(activa.id in activos)
        assertTrue(inactiva.id in inactivos)
        assertTrue(inactiva.id !in activos)
    }

    /**
     * T088, the gap the brief left open: deactivating someone who still has an
     * open shift is allowed, and the daily job will mark it INCOMPLETO. Blocking
     * the exit would hold up an administrative action because a person forgot to
     * clock out - making one person's mistake somebody else's problem.
     */
    @Test
    fun `someone with an open shift can still be deactivated`() {
        val empleado = empleadoService.crear(
            "Persona", dniValido(), "Florista",
            TipoContrato.JORNADA_COMPLETA, LocalDate.parse("2026-10-01")
        )
        val abierto = fichajeService.registrarEntrada(
            empleado.id, madrid("2026-10-05", "07:00"), null
        )

        val inactivo = empleadoService.cambiarActivo(empleado.id, false)

        assertEquals(false, inactivo.activo)
        assertEquals(
            EstadoFichaje.EN_CURSO,
            fichajeService.findById(abierto.id).estado,
            "the shift is left for the daily job to mark INCOMPLETO, not closed by guesswork"
        )
    }
}
