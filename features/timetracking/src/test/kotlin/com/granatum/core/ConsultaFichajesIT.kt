package com.granatum.core

import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.ValoresIncoherentesException
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.domain.type.TipoPausa
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.service.FichajeService
import org.hibernate.SessionFactory
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
 * Querying the register, and the privacy boundary around it.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class ConsultaFichajesIT {

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
            // Needed by the N+1 assertion below, which reads Hibernate's own
            // query counter rather than guessing from annotations.
            registry.add("spring.jpa.properties.hibernate.generate_statistics") { "true" }
        }
    }

    @Autowired lateinit var fichajeService: FichajeService
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var sessionFactory: SessionFactory

    private val madrid = com.granatum.core.api.util.RangoFechas.ZONA

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

    private fun jornada(empleado: EmpleadoEntity, fecha: String, conPausa: Boolean = false) {
        var f = fichajeService.registrarEntrada(empleado.id, madrid(fecha, "07:00"), null)
        if (conPausa) {
            f = fichajeService.iniciarPausa(f.id, empleado.id, madrid(fecha, "11:00"), TipoPausa.COMIDA)
            f = fichajeService.finalizarPausa(f.id, empleado.id, madrid(fecha, "11:30"))
        }
        fichajeService.registrarSalida(f.id, empleado.id, madrid(fecha, "16:00"), null)
    }

    @Test
    fun `a range returns only the shifts inside it, newest first`() {
        val empleado = nuevoEmpleado()
        jornada(empleado, "2026-10-01")
        jornada(empleado, "2026-10-05")
        jornada(empleado, "2026-10-20")

        val resultado = fichajeService.findPorEmpleadoYRango(
            empleado.id, empleado.id, Role.EMPLEADO,
            LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-10")
        )

        assertEquals(2, resultado.size)
        assertTrue(
            resultado[0].entrada.isAfter(resultado[1].entrada),
            "ordered by entry descending"
        )
    }

    /** Both bounds inclusive, interpreted in Europe/Madrid. */
    @Test
    fun `both ends of the range are inclusive`() {
        val empleado = nuevoEmpleado()
        jornada(empleado, "2026-10-05")

        val mismoDia = fichajeService.findPorEmpleadoYRango(
            empleado.id, empleado.id, Role.EMPLEADO,
            LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-05")
        )

        assertEquals(1, mismoDia.size, "a single-day range must include that day")
    }

    /**
     * A shift crossing midnight belongs to the day it started, and must not
     * appear twice.
     */
    @Test
    fun `a shift crossing midnight appears only on the day it started`() {
        val empleado = nuevoEmpleado()
        val f = fichajeService.registrarEntrada(empleado.id, madrid("2026-10-07", "22:00"), null)
        fichajeService.registrarSalida(f.id, empleado.id, madrid("2026-10-08", "06:00"), null)

        val diaInicio = fichajeService.findPorEmpleadoYRango(
            empleado.id, empleado.id, Role.EMPLEADO,
            LocalDate.parse("2026-10-07"), LocalDate.parse("2026-10-07")
        )
        val diaFin = fichajeService.findPorEmpleadoYRango(
            empleado.id, empleado.id, Role.EMPLEADO,
            LocalDate.parse("2026-10-08"), LocalDate.parse("2026-10-08")
        )

        assertEquals(1, diaInicio.size)
        assertEquals(0, diaFin.size, "it must not be counted twice")
    }

    @Test
    fun `an empty range returns an empty list rather than an error`() {
        val empleado = nuevoEmpleado()

        val resultado = fichajeService.findPorEmpleadoYRango(
            empleado.id, empleado.id, Role.EMPLEADO,
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31")
        )

        assertEquals(emptyList(), resultado)
    }

    @Test
    fun `an inverted range is rejected`() {
        val empleado = nuevoEmpleado()

        assertFailsWith<ValoresIncoherentesException> {
            fichajeService.findPorEmpleadoYRango(
                empleado.id, empleado.id, Role.EMPLEADO,
                LocalDate.parse("2026-10-31"), LocalDate.parse("2026-10-01")
            )
        }
    }

    /**
     * SC-004, the privacy boundary: an EMPLEADO cannot reach another person's
     * register **even when naming their id explicitly**, which is the whole
     * point - the path id is for readability, never authority.
     */
    @Test
    fun `an EMPLEADO cannot read another person's register even by naming their id`() {
        val yo = nuevoEmpleado()
        val otro = nuevoEmpleado()
        jornada(otro, "2026-10-05")

        assertFailsWith<ForbiddenException> {
            fichajeService.findPorEmpleadoYRango(
                empleadoIdSolicitado = otro.id,
                solicitanteId = yo.id,
                rol = Role.EMPLEADO,
                desde = LocalDate.parse("2026-10-01"),
                hasta = LocalDate.parse("2026-10-31")
            )
        }
    }

    @Test
    fun `an EMPLEADO cannot list the whole workforce`() {
        val yo = nuevoEmpleado()

        assertFailsWith<ForbiddenException> {
            fichajeService.findTodosPorRango(
                Role.EMPLEADO, LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")
            )
        }
    }

    @Test
    fun `ENCARGADO, ADMIN and REPRESENTANTE can read anyone's register`() {
        val otro = nuevoEmpleado()
        jornada(otro, "2026-10-05")

        listOf(Role.ENCARGADO, Role.ADMIN, Role.REPRESENTANTE).forEach { rol ->
            val resultado = fichajeService.findPorEmpleadoYRango(
                empleadoIdSolicitado = otro.id,
                solicitanteId = UUID.randomUUID(),
                rol = rol,
                desde = LocalDate.parse("2026-10-01"),
                hasta = LocalDate.parse("2026-10-31")
            )
            assertEquals(1, resultado.size, "$rol must be able to read it")
        }
    }

    /**
     * The N+1 check, done by **counting the queries Hibernate actually ran**
     * rather than by reading the annotations - which is the only way to know.
     *
     * Ten days each with a break: without the `@EntityGraph` this is 1 + 10
     * selects, and a manager asking for the whole workforce multiplies that by
     * the headcount. The assertion is deliberately loose about the exact number
     * and strict about the shape: the count must not grow with the number of
     * rows.
     */
    @Test
    fun `listing shifts with their breaks does not grow queries with the number of rows`() {
        val pocos = nuevoEmpleado()
        (1..2).forEach { d -> jornada(pocos, "2026-11-%02d".format(d), conPausa = true) }

        val muchos = nuevoEmpleado()
        (1..10).forEach { d -> jornada(muchos, "2026-11-%02d".format(d), conPausa = true) }

        fun consultasAlListar(empleadoId: UUID): Long {
            val stats = sessionFactory.statistics
            stats.clear()
            val resultado = fichajeService.findPorEmpleadoYRango(
                empleadoId, empleadoId, Role.EMPLEADO,
                LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30")
            )
            // Touch the breaks, so a lazy load would show up in the count.
            resultado.forEach { it.pausas.size }
            return stats.queryExecutionCount + stats.prepareStatementCount
        }

        val con2 = consultasAlListar(pocos.id)
        val con10 = consultasAlListar(muchos.id)

        assertTrue(
            con10 <= con2 + 1,
            "queries must not scale with rows: 2 shifts took $con2, 10 took $con10 - " +
                "that difference is an N+1, and the @EntityGraph is missing or ineffective"
        )
    }
}
