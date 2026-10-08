package com.granatum.core

import com.granatum.core.domain.event.AvisoDominio
import com.granatum.core.domain.event.TipoAviso
import com.granatum.core.domain.model.ValoresFichaje
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.scheduling.AvisoSalidaOlvidadaJob
import com.granatum.core.scheduling.MarcadoFichajesIncompletosJob
import com.granatum.core.service.CorreccionService
import com.granatum.core.service.FichajeService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What this module tells the notices module (feature 008): FR-001 to FR-003,
 * FR-005. Only the publication is under test - who receives what is
 * `notifications`' business, tested there.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
@RecordApplicationEvents
class AvisosTimetrackingIT {

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
            registry.add("timetracking.aviso-sin-salida.cron") { "0 0 4 1 1 *" }
        }
    }

    @Autowired lateinit var eventos: ApplicationEvents
    @Autowired lateinit var fichajes: FichajeService
    @Autowired lateinit var correcciones: CorreccionService
    @Autowired lateinit var empleados: EmpleadoRepository
    @Autowired lateinit var salidaOlvidada: AvisoSalidaOlvidadaJob
    @Autowired lateinit var incompletos: MarcadoFichajesIncompletosJob

    private fun persona(): EmpleadoEntity = empleados.save(
        EmpleadoEntity(
            nombre = "Persona de prueba",
            documentoIdentidad = "T${UUID.randomUUID().toString().take(8).uppercase()}",
            puesto = "Florista",
            tipoContrato = TipoContrato.JORNADA_COMPLETA,
            fechaAlta = LocalDate.parse("2026-01-01"),
            activo = true
        )
    )

    private fun avisos(tipo: TipoAviso) = eventos.stream(AvisoDominio::class.java).filter { it.tipo == tipo }.toList()

    private fun haceHoras(h: Long): Instant = Instant.now().minus(h, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)

    @Test
    fun `a shift open for more than the threshold is announced to its owner, a recent one is not`() {
        val olvidadiza = persona()
        val reciente = persona()
        val viejo = fichajes.registrarEntrada(olvidadiza.id, haceHoras(11), null)
        fichajes.registrarEntrada(reciente.id, haceHoras(2), null)

        salidaOlvidada.avisar()

        val sinSalida = avisos(TipoAviso.FICHAJE_SIN_SALIDA)
        assertTrue(sinSalida.any { it.referenciaId == viejo.id && it.titularId == olvidadiza.id })
        assertTrue(sinSalida.none { it.titularId == reciente.id })
    }

    @Test
    fun `a shift marked incomplete is announced to its owner`() {
        val p = persona()
        val ayer = fichajes.registrarEntrada(p.id, haceHoras(30), null)

        incompletos.marcarIncompletos()

        assertTrue(avisos(TipoAviso.FICHAJE_INCOMPLETO).any { it.referenciaId == ayer.id && it.titularId == p.id })
    }

    @Test
    fun `a correction request and its resolution are announced`() {
        val p = persona()
        val entrada = haceHoras(9)
        val f = fichajes.registrarEntrada(p.id, entrada, null)
        fichajes.registrarSalida(f.id, p.id, haceHoras(1), null)
        val encargado = UUID.randomUUID()

        val pedida = correcciones.solicitar(f.id, p.id, Role.EMPLEADO, "Olvidé una pausa", ValoresFichaje(entrada, haceHoras(2), emptyList()))
        val pendiente = avisos(TipoAviso.CORRECCION_PENDIENTE).single { it.referenciaId == pedida.id }
        assertEquals(p.id, pendiente.autorId, "so the requester is not told about their own request")
        assertEquals(p.id, pendiente.titularId)

        correcciones.aprobar(pedida.id, encargado, Role.ENCARGADO)
        val aprobada = avisos(TipoAviso.CORRECCION_APROBADA).single { it.referenciaId == pedida.id }
        assertEquals(p.id, aprobada.titularId)
    }

    @Test
    fun `a rejected correction is announced to the shift's owner, whoever asked`() {
        val p = persona()
        val entrada = haceHoras(9)
        val f = fichajes.registrarEntrada(p.id, entrada, null)
        fichajes.registrarSalida(f.id, p.id, haceHoras(1), null)
        val encargado = UUID.randomUUID()
        val pedida = correcciones.solicitar(f.id, encargado, Role.ENCARGADO, "Ajuste", ValoresFichaje(entrada, haceHoras(2), emptyList()))

        correcciones.rechazar(pedida.id, UUID.randomUUID(), Role.ADMIN, "No procede")

        assertEquals(p.id, avisos(TipoAviso.CORRECCION_RECHAZADA).single { it.referenciaId == pedida.id }.titularId)
    }
}
