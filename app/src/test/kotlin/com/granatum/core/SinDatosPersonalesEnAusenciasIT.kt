package com.granatum.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.granatum.core.ClientePruebaHttp.Companion.campo
import com.granatum.core.ClientePruebaHttp.Companion.dniValido
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feature 007, FR-021: the comment someone writes on a request and the reason
 * a manager gives for rejecting it never reach the logs - not at DEBUG, where a
 * production diagnostic session runs, and not through the web layer, which
 * logs request and response bodies through their toString().
 *
 * End to end with the real staff register: the person is created through
 * `timetracking` and requests through `absences`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SinDatosPersonalesEnAusenciasIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired lateinit var jwtService: JwtService

    private val http by lazy { ClientePruebaHttp(puerto) }
    private val raiz = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    /**
     * A snapshot of what was captured. Logback appends to the list while
     * holding the appender's lock, and scheduled jobs or server threads may
     * still be logging while the test reads: iterating the live list then
     * throws ConcurrentModificationException (seen once in a full build).
     */
    private fun capturados(): List<ILoggingEvent> = synchronized(appender) { appender.list.toList() }
    private var nivelPrevio: Level? = null

    @BeforeEach
    fun capturar() {
        nivelPrevio = raiz.level
        appender.start()
        raiz.addAppender(appender)
        raiz.level = Level.DEBUG
    }

    @AfterEach
    fun soltar() {
        raiz.detachAppender(appender)
        appender.stop()
        raiz.level = nivelPrevio ?: Level.INFO
    }

    @Test
    fun `comments and rejection reasons stay out of the logs`() {
        val admin = jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)
        val alta = http.post(
            "/api/empleados",
            """{"nombre":"Persona Ausencias","documentoIdentidad":"${dniValido()}","puesto":"Florista","tipoContrato":"PARCIAL","fechaAlta":"2026-01-01"}""",
            admin
        )
        assertEquals(201, alta.estado, alta.cuerpo)
        val empleado = jwtService.generateAccessToken(UUID.fromString(campo(alta.cuerpo, "id")), Role.EMPLEADO)
        val encargado = jwtService.generateAccessToken(UUID.randomUUID(), Role.ENCARGADO)

        val comentario = "Boda-de-mi-hermana-${UUID.randomUUID().toString().take(8)}"
        val motivo = "Inventario-anual-${UUID.randomUUID().toString().take(8)}"
        val anio = LocalDate.now().year + 1

        val pedida = http.post(
            "/api/ausencias",
            """{"tipo":"VACACIONES","desde":"$anio-06-01","hasta":"$anio-06-03","comentario":"$comentario"}""",
            empleado
        )
        assertEquals(201, pedida.estado, pedida.cuerpo)
        val rechazada = http.post("/api/ausencias/${campo(pedida.cuerpo, "id")}/rechazar", """{"motivo":"$motivo"}""", encargado)
        assertEquals(200, rechazada.estado, rechazada.cuerpo)

        assertTrue(capturados().isNotEmpty())
        mapOf("comentario" to comentario, "motivo de rechazo" to motivo).forEach { (que, valor) ->
            val fugas = capturados()
                .filterNot { it.loggerName.startsWith("org.springframework.web.client.") }
                .filter { it.formattedMessage.contains(valor) }
                .map { "[${it.loggerName}] ${it.formattedMessage.take(200)}" }
            assertTrue(fugas.isEmpty(), "the $que reached the logs at DEBUG:\n${fugas.joinToString("\n")}")
        }
    }
}
