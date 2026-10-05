package com.granatum.core

import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.ValoresIncoherentesException
import com.granatum.core.domain.model.ValoresFichaje
import com.granatum.core.domain.model.ValoresPausa
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.domain.type.TipoPausa
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SC-014: the monthly summary adds up, and flags the days that were
 * reconstructed or corrected.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class ResumenMensualIT {

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
    @Autowired lateinit var job: MarcadoFichajesIncompletosJob

    private val madrid = com.granatum.core.api.util.RangoFechas.ZONA
    private val encargado = UUID.randomUUID()

    private fun madrid(fecha: String, hora: String) =
        LocalDateTime.of(LocalDate.parse(fecha), LocalTime.parse(hora)).atZone(madrid).toInstant()

    private fun nuevoEmpleado(tipo: TipoContrato = TipoContrato.JORNADA_COMPLETA): EmpleadoEntity =
        empleadoRepository.save(
            EmpleadoEntity(
                nombre = "Persona de prueba",
                documentoIdentidad = "T${UUID.randomUUID().toString().take(8).uppercase()}",
                puesto = "Florista",
                tipoContrato = tipo,
                fechaAlta = LocalDate.parse("2026-10-01"),
                activo = true
            )
        )

    private fun jornada(empleado: EmpleadoEntity, fecha: String, conPausa: Boolean = false) = run {
        var f = fichajeService.registrarEntrada(empleado.id, madrid(fecha, "07:00"), null)
        if (conPausa) {
            f = fichajeService.iniciarPausa(f.id, empleado.id, madrid(fecha, "11:00"), TipoPausa.COMIDA)
            f = fichajeService.finalizarPausa(f.id, empleado.id, madrid(fecha, "11:30"))
        }
        fichajeService.registrarSalida(f.id, empleado.id, madrid(fecha, "16:00"), null)
    }

    @Test
    fun `the monthly total equals the sum of its days`() {
        val empleado = nuevoEmpleado()
        jornada(empleado, "2026-10-05")                   // 540
        jornada(empleado, "2026-10-06", conPausa = true)  // 510
        jornada(empleado, "2026-10-07")                   // 540

        val resumen = fichajeService.resumenMensual(
            empleado.id, empleado.id, Role.EMPLEADO, 2026, 10
        )

        assertEquals(3, resumen.dias.size)
        assertEquals(1590, resumen.totalMinutosTrabajados)
        assertEquals(
            resumen.dias.sumOf { it.minutosTrabajados ?: 0 },
            resumen.totalMinutosTrabajados,
            "the total must be the sum of the days shown, not an independent count"
        )
        assertEquals(30, resumen.dias.single { it.minutosPausa > 0 }.minutosPausa)
    }

    @Test
    fun `days outside the month are excluded`() {
        val empleado = nuevoEmpleado()
        jornada(empleado, "2026-09-30")
        jornada(empleado, "2026-10-01")
        jornada(empleado, "2026-11-01")

        val resumen = fichajeService.resumenMensual(
            empleado.id, empleado.id, Role.EMPLEADO, 2026, 10
        )

        assertEquals(1, resumen.dias.size)
        assertEquals(LocalDate.parse("2026-10-01"), resumen.dias.single().fecha)
    }

    /** FR-034: the summary shows the values in force, not the originals. */
    @Test
    fun `a corrected day shows the corrected values and is flagged`() {
        val empleado = nuevoEmpleado()
        val fichaje = jornada(empleado, "2026-10-05")
        assertEquals(540, fichaje.minutosTrabajados)

        val solicitud = correccionService.solicitar(
            fichaje.id, empleado.id, Role.EMPLEADO, "Olvide fichar la pausa de comida",
            ValoresFichaje(
                madrid("2026-10-05", "07:00"), madrid("2026-10-05", "16:00"),
                listOf(ValoresPausa(TipoPausa.COMIDA, madrid("2026-10-05", "11:00"), madrid("2026-10-05", "11:45")))
            )
        )
        correccionService.aprobar(solicitud.id, encargado, Role.ENCARGADO)

        val resumen = fichajeService.resumenMensual(
            empleado.id, empleado.id, Role.EMPLEADO, 2026, 10
        )
        val dia = resumen.dias.single()

        assertEquals(495, dia.minutosTrabajados, "the corrected value, not the original 540")
        assertEquals(45, dia.minutosPausa)
        assertTrue(dia.corregido, "the day must be flagged so the reader knows it was changed")
        assertFalse(dia.reconstruido, "it was closed at the time; only its values were corrected")
    }

    /** SC-010 carried through to the summary. */
    @Test
    fun `a reconstructed day is flagged as such`() {
        val empleado = nuevoEmpleado()
        val ayer = LocalDate.now(madrid).minusDays(1)

        val olvidado = fichajeService.registrarEntrada(
            empleado.id,
            LocalDateTime.of(ayer, LocalTime.parse("08:00")).atZone(madrid).toInstant(),
            null
        )
        job.marcarIncompletos()

        val solicitud = correccionService.solicitar(
            olvidado.id, empleado.id, Role.EMPLEADO, "Olvide fichar la salida",
            ValoresFichaje(
                LocalDateTime.of(ayer, LocalTime.parse("08:00")).atZone(madrid).toInstant(),
                LocalDateTime.of(ayer, LocalTime.parse("16:00")).atZone(madrid).toInstant(),
                emptyList()
            )
        )
        correccionService.aprobar(solicitud.id, encargado, Role.ENCARGADO)

        val resumen = fichajeService.resumenMensual(
            empleado.id, empleado.id, Role.EMPLEADO, ayer.year, ayer.monthValue
        )
        val dia = resumen.dias.single { it.fecha == ayer }

        assertTrue(
            dia.reconstruido,
            "an inspection report must be able to say which days were rebuilt"
        )
        assertTrue(dia.corregido)
        assertEquals(480, dia.minutosTrabajados)
    }

    /**
     * An open or INCOMPLETO day contributes nothing to the total, and shows a
     * null rather than a zero. A zero would read as "worked nothing", which is a
     * different and false claim.
     */
    @Test
    fun `an unresolved day shows null instead of contributing zero silently`() {
        val empleado = nuevoEmpleado()
        jornada(empleado, "2026-10-05")
        fichajeService.registrarEntrada(empleado.id, madrid("2026-10-06", "07:00"), null)

        val resumen = fichajeService.resumenMensual(
            empleado.id, empleado.id, Role.EMPLEADO, 2026, 10
        )

        assertEquals(2, resumen.dias.size)
        assertEquals(540, resumen.totalMinutosTrabajados, "only the closed day counts")
        assertEquals(
            1,
            resumen.dias.count { it.minutosTrabajados == null },
            "the open day must be visible as unresolved, not as zero"
        )
    }

    @Test
    fun `the contract type travels with the summary`() {
        val empleado = nuevoEmpleado(TipoContrato.PARCIAL)
        jornada(empleado, "2026-10-05")

        val resumen = fichajeService.resumenMensual(
            empleado.id, empleado.id, Role.EMPLEADO, 2026, 10
        )

        assertEquals(
            TipoContrato.PARCIAL,
            resumen.tipoContrato,
            "article 12.4.c obliges a monthly summary for part-time staff, so the " +
                "consumer has to know whether this is one of those"
        )
    }

    @Test
    fun `an EMPLEADO cannot read another person's summary`() {
        val yo = nuevoEmpleado()
        val otro = nuevoEmpleado()
        jornada(otro, "2026-10-05")

        assertFailsWith<ForbiddenException> {
            fichajeService.resumenMensual(otro.id, yo.id, Role.EMPLEADO, 2026, 10)
        }
    }

    @Test
    fun `an invalid month is rejected`() {
        val empleado = nuevoEmpleado()

        assertFailsWith<ValoresIncoherentesException> {
            fichajeService.resumenMensual(empleado.id, empleado.id, Role.EMPLEADO, 2026, 13)
        }
        assertFailsWith<ValoresIncoherentesException> {
            fichajeService.resumenMensual(empleado.id, empleado.id, Role.EMPLEADO, 2026, 0)
        }
    }
}
