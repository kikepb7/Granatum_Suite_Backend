package com.granatum.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.entities.CuentaAccesoEntity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SC-009 and principle VI: no password, no token and no email address in the
 * logs - verified over the complete output of a full credential lifecycle.
 *
 * ## Why the root logger is raised to DEBUG
 *
 * On purpose: that is exactly the state a diagnostic session in production puts
 * the application in, and it is where leaks hide. In feature 001 this same test
 * found Hibernate's `EntityPrinter` dumping the identity document by reflection,
 * bypassing the entity's careful `toString()`.
 *
 * ## Why over HTTP and not through the services
 *
 * Because the most likely leak is in the web layer, not in this module's code.
 * At DEBUG, Spring MVC logs every deserialised request body and every response
 * body through its `toString()` - "Read application/json to [LoginRequest(...)]"
 * - and a Kotlin `data class` prints every field. Calling the services directly
 * would never pass through that logger, and the test would stay green over the
 * one route by which a password actually reaches a log file.
 */
@Testcontainers
@SpringBootTest(
    classes = [AuthTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(SeguridadPermisivaTestConfig::class)
class SinDatosPersonalesEnLogsIT : BaseAuthIT() {

    @LocalServerPort
    var puerto: Int = 0

    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var raiz: Logger

    @BeforeEach
    fun capturarLogs() {
        raiz = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        raiz.addAppender(appender)
        raiz.level = Level.DEBUG
    }

    @AfterEach
    fun soltarLogs() {
        raiz.detachAppender(appender)
        appender.stop()
        raiz.level = Level.INFO
    }

    /**
     * The test's own HTTP client logs what it sends, and that is not the
     * server: in production nothing calls this API through Spring's
     * `RestClient` from inside the application. Excluded by logger name, and
     * only that one, so a server-side leak can never hide behind it.
     */
    private fun esDelClienteDelTest(evento: ILoggingEvent): Boolean =
        evento.loggerName.startsWith("org.springframework.web.client.")

    private fun lineasQueContienen(texto: String): List<String> =
        appender.list
            .filterNot { esDelClienteDelTest(it) }
            .filter { (it.formattedMessage + (it.throwableProxy?.message ?: "")).contains(texto) }
            .map { "[${it.loggerName}] ${it.formattedMessage.take(200)}" }

    private fun post(ruta: String, json: String, auth: String? = null): String =
        RestClient.builder().baseUrl("http://localhost:$puerto").build()
            .post().uri(ruta)
            .contentType(MediaType.APPLICATION_JSON)
            .apply { if (auth != null) header("Authorization", auth) }
            .body(json)
            .exchange({ _, response -> response.body.readAllBytes().decodeToString() }, false)!!

    private fun campo(cuerpo: String, nombre: String): String =
        Regex("\"$nombre\"\\s*:\\s*\"([^\"]*)\"").find(cuerpo)!!.groupValues[1]

    @Test
    fun `a full credential lifecycle leaves no secret and no email in the logs`() {
        val empleadoId = UUID.randomUUID().also { directorio.registrarActivo(it) }
        val email = "fuga-${UUID.randomUUID()}@granatum.es"
        val definitiva = "Fuga-Definitiva-${UUID.randomUUID().toString().take(8)}!1"

        val alta = post("/api/auth/cuentas", """{"empleadoId":"$empleadoId","email":"$email"}""")
        val temporal = campo(alta, "passwordTemporal")

        val primerLogin = post("/api/auth/login", """{"email":"$email","password":"$temporal"}""")
        val accesoPendiente = campo(primerLogin, "accessToken")

        val cambio = post(
            "/api/auth/change-password",
            """{"passwordActual":"$temporal","passwordNueva":"$definitiva"}""",
            auth = "Bearer $accesoPendiente"
        )
        val refresh = campo(cambio, "refreshToken")
        val acceso = campo(cambio, "accessToken")

        val renovado = post("/api/auth/refresh", """{"refreshToken":"$refresh"}""")
        post("/api/auth/logout", """{"refreshToken":"${campo(renovado, "refreshToken")}"}""")

        // A failed attempt too: error paths are where exception messages get
        // logged with whatever the developer had to hand.
        post("/api/auth/login", """{"email":"$email","password":"Incorrecta-${UUID.randomUUID()}"}""")

        val reset = post("/api/auth/cuentas/$empleadoId/restablecer", "")
        val temporalReset = campo(reset, "passwordTemporal")

        val secretos = mapOf(
            "email" to email,
            "contraseña temporal" to temporal,
            "contraseña definitiva" to definitiva,
            "contraseña temporal del reset" to temporalReset,
            "token de acceso" to acceso,
            "token de renovacion" to refresh
        )

        assertTrue(appender.list.isNotEmpty(), "the appender must have captured something")

        secretos.forEach { (que, valor) ->
            val fugas = lineasQueContienen(valor)
            assertFalse(
                fugas.isNotEmpty(),
                "the $que reached the logs at DEBUG - which is exactly the level a " +
                    "production diagnostic session runs at:\n${fugas.joinToString("\n")}"
            )
        }
    }

    /** The quiet route: debuggers, log statements and stack traces all reach for it. */
    @Test
    fun `the account entity toString carries neither the email nor the hash`() {
        val cuenta = CuentaAccesoEntity(
            empleadoId = UUID.randomUUID(),
            email = "tostring@granatum.es",
            rol = Role.EMPLEADO,
            passwordHash = "{argon2}\$argon2id\$v=19\$m=1024,t=1,p=1\$sal\$hash"
        )

        assertFalse(cuenta.toString().contains("tostring@granatum.es"))
        assertFalse(cuenta.toString().contains("argon2"))
    }
}
