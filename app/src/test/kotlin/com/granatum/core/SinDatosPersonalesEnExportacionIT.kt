package com.granatum.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Principle VI for feature 003: exporting and verifying put no personal data in
 * the logs, with the root logger at DEBUG - the state a production diagnostic
 * session leaves the application in.
 *
 * The lesson of feature 002 is that at DEBUG Spring MVC logs request and
 * response bodies through `toString()`, and that is how passwords leaked. Here
 * the stakes are a whole register: the export streams names and identity
 * documents, and the verification endpoint *receives* a file full of them.
 *
 * Seeded through JDBC so that only the export calls run while logs are
 * captured. The test's own HTTP client is excluded: what it logs is on the
 * client side, not the server's.
 *
 * Validated by mutation (2026-10-07): a temporary
 * `log.debug("exportando {}", persona.nombre)` in `ExportacionService` turned it
 * red with the offending line quoted; restored afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SinDatosPersonalesEnExportacionIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var dataSource: DataSource

    private val cliente by lazy { RestClient.builder().baseUrl("http://localhost:$puerto").build() }
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

    @AfterEach
    fun soltarLogs() {
        raiz.detachAppender(appender)
        appender.stop()
        raiz.level = nivelPrevio ?: Level.INFO
    }

    private val nombre = "Nombreunico ${UUID.randomUUID().toString().take(6)}"
    private val documento = "Q${(10_000_000..99_999_999).random()}X"
    private val latitud = "39.469907"
    private val longitud = "-0.376288"

    private fun sembrar(): UUID {
        val empleado = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO empleados (id, nombre, documento_identidad, puesto, tipo_contrato,
                                       fecha_alta, activo, created_at, updated_at)
                VALUES (?, ?, ?, 'Florista', 'PARCIAL', '2020-01-01', TRUE, now(), now())
                """.trimIndent()
            ).use { s ->
                s.setObject(1, empleado); s.setString(2, nombre); s.setString(3, documento); s.executeUpdate()
            }
            c.prepareStatement(
                """
                INSERT INTO fichajes (id, empleado_id, entrada, salida, estado, minutos_trabajados,
                                      fue_incompleto, ubicacion_entrada_latitud, ubicacion_entrada_longitud,
                                      created_at, updated_at)
                VALUES (?, ?, TIMESTAMPTZ '2025-02-11 08:00:00+01', TIMESTAMPTZ '2025-02-11 14:00:00+01',
                        'CERRADO', 360, FALSE, ?::numeric, ?::numeric, now(), now())
                """.trimIndent()
            ).use { s ->
                s.setObject(1, UUID.randomUUID()); s.setObject(2, empleado)
                s.setString(3, latitud); s.setString(4, longitud); s.executeUpdate()
            }
        }
        return empleado
    }

    private fun descargar(ruta: String): ByteArray =
        cliente.get().uri(ruta)
            .header("Authorization", "Bearer ${jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)}")
            .exchange({ _, r -> assertEquals(200, r.statusCode.value()); r.body.readAllBytes() }, false)!!

    @Test
    fun `exporting, downloading a month and verifying leave no name, document or coordinate in the logs`() {
        val empleado = sembrar()

        appender.start()
        raiz.addAppender(appender)
        nivelPrevio = raiz.level
        raiz.level = Level.DEBUG

        val plantilla = descargar("/api/fichajes/export?desde=2025-02-11&hasta=2025-02-11")
        val mensual = descargar("/api/fichajes/empleado/$empleado/resumen/descarga?anio=2025&mes=2")
        val verificacion = cliente.post().uri("/api/exportaciones/verificar")
            .header("Authorization", "Bearer ${jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)}")
            .contentType(MediaType("text", "csv"))
            .body(plantilla)
            .exchange({ _, r -> r.statusCode.value() }, false)

        // The calls did carry the data: otherwise a clean log would prove nothing.
        assertTrue(String(plantilla, Charsets.UTF_8).contains(documento))
        assertTrue(String(mensual, Charsets.UTF_8).contains(nombre))
        assertEquals(200, verificacion)
        assertTrue(capturados().isNotEmpty(), "DEBUG was on and something was logged")

        val delServidor = capturados().filterNot { it.loggerName.startsWith("org.springframework.web.client") }
        val fugas = listOf(nombre, documento, latitud, longitud).flatMap { dato ->
            delServidor
                .filter { (it.formattedMessage + (it.throwableProxy?.message ?: "")).contains(dato) }
                .map { "[$dato] [${it.loggerName}] ${it.formattedMessage.take(200)}" }
        }
        assertEquals(emptyList(), fugas, "personal data reached the logs")
    }
}
