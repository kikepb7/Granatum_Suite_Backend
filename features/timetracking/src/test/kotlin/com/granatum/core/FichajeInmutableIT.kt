package com.granatum.core

import com.granatum.core.domain.exception.FichajeNoEnCursoException
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **The test constitution principle III requires**, and the one
 * `/speckit-analyze` was told to look for: a finalised fichaje does not change
 * value through any route other than an approved correction (FR-018, SC-002).
 *
 * Written as an exhaustive attempt rather than a single assertion. The claim
 * being made is a negative one - "there is no way to do this" - so the test has
 * to try the ways there might be and then prove nothing moved.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class FichajeInmutableIT {

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
    @Autowired lateinit var empleadoRepository: EmpleadoRepository

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

    @Test
    fun `no service operation can alter a closed fichaje`() {
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
        val cerrado = fichajeService.registrarSalida(
            fichaje.id, empleado.id, madrid("2026-10-05", "16:00"), null
        )

        // Snapshot every value that matters before trying to move it.
        val entradaOriginal = cerrado.entrada
        val salidaOriginal = cerrado.salida
        val minutosOriginal = cerrado.minutosTrabajados
        val pausasOriginal = cerrado.pausas.map { it.id to (it.inicio to it.fin) }.toMap()
        val estadoOriginal = cerrado.estado

        // Every write operation the service exposes, in turn. All four require
        // an EN_CURSO fichaje, so all four must refuse.
        assertFailsWith<FichajeNoEnCursoException> {
            fichajeService.iniciarPausa(
                cerrado.id, empleado.id, madrid("2026-10-05", "17:00"), TipoPausa.OTRO
            )
        }
        assertFailsWith<FichajeNoEnCursoException> {
            fichajeService.finalizarPausa(cerrado.id, empleado.id, madrid("2026-10-05", "17:30"))
        }
        assertFailsWith<FichajeNoEnCursoException> {
            fichajeService.registrarSalida(
                cerrado.id, empleado.id, madrid("2026-10-05", "20:00"), null
            )
        }

        // And nothing moved.
        val tras = fichajeService.findById(cerrado.id)
        assertEquals(entradaOriginal, tras.entrada)
        assertEquals(salidaOriginal, tras.salida)
        assertEquals(minutosOriginal, tras.minutosTrabajados)
        assertEquals(estadoOriginal, tras.estado)
        assertEquals(
            pausasOriginal,
            tras.pausas.map { it.id to (it.inicio to it.fin) }.toMap(),
            "the breaks must be identical, not merely the same count"
        )
    }

    /**
     * A second clock-in does not reopen the closed day either - it creates a new
     * one. Worth asserting separately: "clock in again" is the most plausible
     * accidental route to mutating yesterday's record.
     */
    @Test
    fun `clocking in again creates a new fichaje instead of reopening the closed one`() {
        val empleado = nuevoEmpleado()
        val primero = fichajeService.registrarEntrada(
            empleado.id, madrid("2026-10-05", "07:00"), null
        )
        val cerrado = fichajeService.registrarSalida(
            primero.id, empleado.id, madrid("2026-10-05", "15:00"), null
        )

        val segundo = fichajeService.registrarEntrada(
            empleado.id, madrid("2026-10-06", "07:00"), null
        )

        assertTrue(segundo.id != cerrado.id, "a new day must be a new fichaje")

        val original = fichajeService.findById(cerrado.id)
        assertEquals(480, original.minutosTrabajados)
        assertEquals(madrid("2026-10-05", "15:00"), original.salida)
    }

    /**
     * The HTTP surface carries no update route at all.
     *
     * Asserted by reflection over the controller rather than by calling an
     * endpoint, because the point is the *absence* of one: a PUT or PATCH on
     * `/api/fichajes/{id}` is exactly how principle III would be broken without
     * anyone noticing, and this fails the build the moment someone adds it.
     */
    @Test
    fun `the fichaje controller exposes no update or delete route`() {
        val metodos = com.granatum.core.api.controllers.FichajeController::class.java.methods

        val prohibidos = metodos.filter { m ->
            m.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping::class.java) ||
                m.isAnnotationPresent(org.springframework.web.bind.annotation.PatchMapping::class.java) ||
                m.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping::class.java)
        }.map { it.name }

        assertEquals(
            emptyList(),
            prohibidos,
            "FichajeController must expose no PUT, PATCH or DELETE: a finalised " +
                "fichaje changes only through an approved correction"
        )
    }
}
