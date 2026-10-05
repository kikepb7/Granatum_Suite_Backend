package com.granatum.core

import com.granatum.core.api.controllers.FichajeController
import com.granatum.core.api.dto.EntradaRequest
import com.granatum.core.api.dto.SalidaRequest
import com.granatum.core.domain.exception.ClientEventIdReutilizadoException
import com.granatum.core.domain.exception.DesviacionRelojException
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * **The test the brief asked for by name**: resending an operation must not
 * duplicate it.
 *
 * Driven through the controller rather than the service, because idempotency
 * lives between the two: calling the service directly would bypass exactly the
 * layer under test.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class ReenvioDuplicadoIT {

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

    @Autowired lateinit var controller: FichajeController
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var dataSource: DataSource

    private lateinit var empleado: EmpleadoEntity

    @BeforeEach
    fun autenticar() {
        empleado = empleadoRepository.save(
            EmpleadoEntity(
                nombre = "Persona de prueba",
                documentoIdentidad = "T${UUID.randomUUID().toString().take(8).uppercase()}",
                puesto = "Florista",
                tipoContrato = TipoContrato.JORNADA_COMPLETA,
                fechaAlta = java.time.LocalDate.parse("2026-10-01"),
                activo = true
            )
        )
        // The controller reads the subject from the security context, which is
        // what production does; the empleado id *is* the JWT subject.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                empleado.id, null, listOf(SimpleGrantedAuthority("ROLE_EMPLEADO"))
            )
    }

    @AfterEach
    fun limpiar() = SecurityContextHolder.clearContext()

    private fun contarFichajes(): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM fichajes WHERE empleado_id = ?"
            ).use { statement ->
                statement.setObject(1, empleado.id)
                val rs = statement.executeQuery()
                rs.next()
                rs.getInt(1)
            }
        }

    /** SC-005. The same request, twice. */
    @Test
    fun `resending the same operation returns the same fichaje and creates only one`() {
        val peticion = EntradaRequest(
            clientEventId = UUID.randomUUID(),
            occurredAt = Instant.now().minus(Duration.ofMinutes(30))
        )

        val primera = controller.registrarEntrada(peticion)
        val segunda = controller.registrarEntrada(peticion)

        assertEquals(
            primera.id,
            segunda.id,
            "the retry must answer with the original fichaje. A 409 FICHAJE_YA_EN_CURSO " +
                "here would mean idempotency is not implemented and the retry is being " +
                "treated as a fresh clock-in"
        )
        assertEquals(primera, segunda, "the whole response must be the original one, verbatim")
        assertEquals(1, contarFichajes(), "exactly one row")
    }

    /**
     * The retry answers with what the **first** call returned, not with current
     * state.
     *
     * Asserted by letting the shift move on in between: the day is closed
     * between the two sends, so a handler that recomputed would answer CERRADO
     * with its worked minutes, while the stored response says EN_CURSO. This is
     * the case that makes "store the response" rather than "recompute it" the
     * right design, and the one a looser test would miss.
     */
    @Test
    fun `the retry returns the original response and not the current state`() {
        val entrada = EntradaRequest(
            clientEventId = UUID.randomUUID(),
            occurredAt = Instant.now().minus(Duration.ofHours(8))
        )
        val primera = controller.registrarEntrada(entrada)

        controller.registrarSalida(
            primera.id,
            SalidaRequest(clientEventId = UUID.randomUUID(), occurredAt = Instant.now())
        )

        val reenvio = controller.registrarEntrada(entrada)

        assertEquals(
            primera,
            reenvio,
            "the shift closed in between, so recomputing would answer CERRADO with its " +
                "minutes; the stored response must still say what it said the first time"
        )
    }

    /** Same key, different body: a client bug, surfaced rather than absorbed. */
    @Test
    fun `the same key with a different body is a conflict`() {
        val clave = UUID.randomUUID()
        val ahora = Instant.now().minus(Duration.ofMinutes(10))

        controller.registrarEntrada(EntradaRequest(clientEventId = clave, occurredAt = ahora))

        assertFailsWith<ClientEventIdReutilizadoException> {
            controller.registrarEntrada(
                EntradaRequest(
                    clientEventId = clave,
                    occurredAt = ahora.plus(Duration.ofMinutes(5))
                )
            )
        }
    }

    /** FR-025: the shift carries the device's time, not the server's. */
    @Test
    fun `the shift is recorded with occurredAt and both instants are kept apart`() {
        val ocurrio = Instant.now().minus(Duration.ofHours(2))
        val fichaje = controller.registrarEntrada(
            EntradaRequest(clientEventId = UUID.randomUUID(), occurredAt = ocurrio)
        )

        assertEquals(
            ocurrio.epochSecond,
            fichaje.entrada.epochSecond,
            "the register must hold when it happened, not when it arrived"
        )

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT occurred_at, received_at FROM fichaje_eventos WHERE fichaje_id = ?"
            ).use { statement ->
                statement.setObject(1, fichaje.id)
                val rs = statement.executeQuery()
                rs.next()
                val occurredAt = rs.getTimestamp("occurred_at").toInstant()
                val receivedAt = rs.getTimestamp("received_at").toInstant()

                assertEquals(ocurrio.epochSecond, occurredAt.epochSecond)
                assertEquals(
                    true,
                    receivedAt.isAfter(occurredAt),
                    "arrival must be recorded separately and later, not conflated"
                )
            }
        }
    }

    @Test
    fun `an operation dated in the future is rejected`() {
        assertFailsWith<DesviacionRelojException> {
            controller.registrarEntrada(
                EntradaRequest(
                    clientEventId = UUID.randomUUID(),
                    occurredAt = Instant.now().plus(Duration.ofMinutes(10))
                )
            )
        }
    }

    @Test
    fun `an operation from 48 hours ago is accepted`() {
        val fichaje = controller.registrarEntrada(
            EntradaRequest(
                clientEventId = UUID.randomUUID(),
                occurredAt = Instant.now().minus(Duration.ofHours(48))
            )
        )
        assertEquals(1, contarFichajes())
        assertEquals(empleado.id, fichaje.empleadoId)
    }

    @Test
    fun `an operation from five days ago is rejected`() {
        assertFailsWith<DesviacionRelojException> {
            controller.registrarEntrada(
                EntradaRequest(
                    clientEventId = UUID.randomUUID(),
                    occurredAt = Instant.now().minus(Duration.ofDays(5))
                )
            )
        }
    }

    /**
     * A rejected clock skew must leave no trace. Were the event stored, the bad
     * timestamp would become permanent and the key unusable for the corrected
     * retry - which is why the clock is validated before the idempotency
     * lookup, not after.
     */
    @Test
    fun `a clock-skew rejection records no event and leaves the key usable`() {
        val clave = UUID.randomUUID()

        assertFailsWith<DesviacionRelojException> {
            controller.registrarEntrada(
                EntradaRequest(clientEventId = clave, occurredAt = Instant.now().plus(Duration.ofHours(1)))
            )
        }
        assertEquals(0, contarFichajes())

        // The same key now works with a plausible time.
        val fichaje = controller.registrarEntrada(
            EntradaRequest(clientEventId = clave, occurredAt = Instant.now().minus(Duration.ofMinutes(5)))
        )
        assertEquals(1, contarFichajes())
        assertEquals(empleado.id, fichaje.empleadoId)
    }
}
