package com.granatum.core

import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.infrastructure.database.repositories.FichajeRepository
import com.granatum.core.scheduling.MarcadoFichajesIncompletosJob
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
import kotlin.test.assertTrue

/**
 * SC-009 and SC-010: a shift left open from a previous day becomes INCOMPLETO,
 * stops blocking new shifts, and stays distinguishable afterwards.
 *
 * The job is invoked directly rather than waiting for its cron, which is the
 * only practical way to test a once-a-day schedule.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class MarcadoFichajesIncompletosIT {

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
            // Never fires on its own during the test: the job is called directly.
            registry.add("timetracking.incompletos.cron") { "0 0 4 1 1 *" }
        }
    }

    @Autowired lateinit var job: MarcadoFichajesIncompletosJob
    @Autowired lateinit var fichajeService: FichajeService
    @Autowired lateinit var fichajeRepository: FichajeRepository
    @Autowired lateinit var empleadoRepository: EmpleadoRepository

    private val madrid = com.granatum.core.api.util.RangoFechas.ZONA

    private fun ayerALas(hora: String) =
        LocalDateTime.of(LocalDate.now(madrid).minusDays(1), LocalTime.parse(hora))
            .atZone(madrid).toInstant()

    private fun hoyALas(hora: String) =
        LocalDateTime.of(LocalDate.now(madrid), LocalTime.parse(hora))
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

    @Test
    fun `a shift left open yesterday becomes INCOMPLETO and stops blocking new ones`() {
        val empleado = nuevoEmpleado()
        val olvidado = fichajeService.registrarEntrada(empleado.id, ayerALas("08:00"), null)

        job.marcarIncompletos()

        val recargado = fichajeRepository.findById(olvidado.id).orElseThrow()
        assertEquals(EstadoFichaje.INCOMPLETO, recargado.estado)
        assertTrue(recargado.fueIncompleto, "the flag must record that it was ever incomplete")

        // FR-012a: the person can clock in today. Locking them out would turn
        // one forgotten clock-out into a second, larger gap in the register.
        val hoy = fichajeService.registrarEntrada(empleado.id, hoyALas("08:00"), null)
        assertEquals(EstadoFichaje.EN_CURSO, hoy.estado)
    }

    @Test
    fun `a shift opened today is left alone`() {
        val empleado = nuevoEmpleado()
        val deHoy = fichajeService.registrarEntrada(empleado.id, hoyALas("08:00"), null)

        job.marcarIncompletos()

        val recargado = fichajeRepository.findById(deHoy.id).orElseThrow()
        assertEquals(
            EstadoFichaje.EN_CURSO,
            recargado.estado,
            "today's shift is still in progress; marking it would be wrong, not merely early"
        )
        assertEquals(false, recargado.fueIncompleto)
    }

    /**
     * Running twice must change nothing the first run did not already do. That
     * is what makes the job safe on several application instances at once
     * without any locking.
     */
    @Test
    fun `running the job twice is idempotent`() {
        val empleado = nuevoEmpleado()
        val olvidado = fichajeService.registrarEntrada(empleado.id, ayerALas("08:00"), null)

        job.marcarIncompletos()
        val tras1 = fichajeRepository.findById(olvidado.id).orElseThrow()
        val estadoTras1 = tras1.estado
        val flagTras1 = tras1.fueIncompleto

        job.marcarIncompletos()
        val tras2 = fichajeRepository.findById(olvidado.id).orElseThrow()

        assertEquals(estadoTras1, tras2.estado)
        assertEquals(flagTras1, tras2.fueIncompleto)
    }
}
