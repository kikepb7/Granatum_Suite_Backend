package com.granatum.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.entities.AccountEntity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import com.granatum.core.service.JwtService
import org.springframework.beans.factory.annotation.Autowired
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
@Import(AuthPermissiveSecurityTestConfig::class)
class AuthNoPersonalDataInLogsIT : BaseAuthIT() {

    @LocalServerPort
    var port: Int = 0

    private lateinit var appender: ListAppender<ILoggingEvent>

    /**
     * A snapshot of what was captured. Logback appends to the list while
     * holding the appender's lock, and scheduled jobs or server threads may
     * still be logging while the test reads: iterating the live list then
     * throws ConcurrentModificationException (seen once in a full build).
     */
    private fun captured(): List<ILoggingEvent> = synchronized(appender) { appender.list.toList() }
    private lateinit var root: Logger

    @BeforeEach
    fun captureLogs() {
        root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        root.addAppender(appender)
        root.level = Level.DEBUG
    }

    @AfterEach
    fun releaseLogs() {
        root.detachAppender(appender)
        appender.stop()
        root.level = Level.INFO
    }

    /**
     * The test's own HTTP client logs what it sends, and that is not the
     * server: in production nothing calls this API through Spring's
     * `RestClient` from inside the application. Excluded by logger name, and
     * only that one, so a server-side leak can never hide behind it.
     */
    private fun isFromTestClient(event: ILoggingEvent): Boolean =
        event.loggerName.startsWith("org.springframework.web.client.")

    private fun linesContaining(text: String): List<String> =
        captured()
            .filterNot { isFromTestClient(it) }
            .filter { (it.formattedMessage + (it.throwableProxy?.message ?: "")).contains(text) }
            .map { "[${it.loggerName}] ${it.formattedMessage.take(200)}" }

    private fun post(path: String, json: String, auth: String? = null): String =
        RestClient.builder().baseUrl("http://localhost:$port").build()
            .post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .apply { if (auth != null) header("Authorization", auth) }
            .body(json)
            .exchange({ _, response -> response.body.readAllBytes().decodeToString() }, false)!!

    private fun field(body: String, name: String): String =
        Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(body)!!.groupValues[1]

    @Test
    fun `a full credential lifecycle leaves no secret and no email in the logs`() {
        val employeeId = UUID.randomUUID().also { directory.registerActive(it) }
        val email = "fuga-${UUID.randomUUID()}@granatum.es"
        val finalPassword = "Fuga-Definitiva-${UUID.randomUUID().toString().take(8)}!1"

        val onboarding = post("/api/auth/accounts", """{"employeeId":"$employeeId","email":"$email"}""")
        val temporaryPassword = field(onboarding, "temporaryPassword")

        val firstLogin = post("/api/auth/login", """{"email":"$email","password":"$temporaryPassword"}""")
        val pendingAccess = field(firstLogin, "accessToken")

        val change = post(
            "/api/auth/change-password",
            """{"currentPassword":"$temporaryPassword","newPassword":"$finalPassword"}""",
            auth = "Bearer $pendingAccess"
        )
        val refresh = field(change, "refreshToken")
        val access = field(change, "accessToken")

        val renewed = post("/api/auth/refresh", """{"refreshToken":"$refresh"}""")
        post("/api/auth/logout", """{"refreshToken":"${field(renewed, "refreshToken")}"}""")

        // A failed attempt too: error paths are where exception messages get
        // logged with whatever the developer had to hand.
        post("/api/auth/login", """{"email":"$email","password":"Incorrecta-${UUID.randomUUID()}"}""")

        val reset = post("/api/auth/accounts/$employeeId/reset", "")
        val resetTemporaryPassword = field(reset, "temporaryPassword")

        val secrets = mapOf(
            "email" to email,
            "contraseña temporal" to temporaryPassword,
            "contraseña definitiva" to finalPassword,
            "contraseña temporal del reset" to resetTemporaryPassword,
            "token de acceso" to access,
            "token de renovacion" to refresh
        )

        assertTrue(captured().isNotEmpty(), "the appender must have captured something")

        secrets.forEach { (what, value) ->
            val leaks = linesContaining(value)
            assertFalse(
                leaks.isNotEmpty(),
                "the $what reached the logs at DEBUG - which is exactly the level a " +
                    "production diagnostic session runs at:\n${leaks.joinToString("\n")}"
            )
        }
    }

    @Autowired lateinit var jwtService: JwtService

    /**
     * Feature 009: onboarding carries a name, a DNI, an email and a temporary
     * password, and the owner's sign-up a bootstrap code - all through the same
     * web-layer loggers. Every branch: onboarding, first sign-in with the
     * temporary password, the forced change, and a refused sign-up.
     */
    @Test
    fun `onboarding a person and the owner's sign-up leave no personal data in the logs`() {
        val admin = "Bearer ${jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)}"
        val email = "alta-${UUID.randomUUID()}@granatum.es"
        val name = "Nombre-Fuga-${UUID.randomUUID().toString().take(6)}"
        val lastName = "Apellidos-Fuga-${UUID.randomUUID().toString().take(6)}"
        val number = (10_000_000..99_999_999).random()
        val identityDocument = "$number${"TRWAGMYFPDXBNJZSQVHLCKE"[number % 23]}"
        val finalPassword = "Alta-Fuga-${UUID.randomUUID().toString().take(8)}!1"

        val onboarding = post(
            "/api/auth/onboarding",
            """{"name":"$name","lastName":"$lastName","identityDocument":"$identityDocument","position":"Florista","contractType":"PART_TIME","hireDate":"2026-10-01","email":"$email","role":"EMPLOYEE"}""",
            admin
        )
        val temporaryPassword = field(onboarding, "temporaryPassword")
        val firstLogin = post("/api/auth/login", """{"email":"$email","password":"$temporaryPassword"}""")
        post(
            "/api/auth/change-password",
            """{"currentPassword":"$temporaryPassword","newPassword":"$finalPassword"}""",
            auth = "Bearer ${field(firstLogin, "accessToken")}"
        )

        val bootstrap = "codigo-de-arranque-${UUID.randomUUID()}"
        post(
            "/api/auth/sign-up",
            """{"email":"jefe-${UUID.randomUUID()}@granatum.es","password":"$finalPassword","name":"$name","lastName":"$lastName","identityDocument":"$identityDocument","bootstrapCode":"$bootstrap"}"""
        )

        val secrets = mapOf(
            "email" to email,
            "name" to name,
            "apellidos" to lastName,
            "documento" to identityDocument,
            "contraseña temporal" to temporaryPassword,
            "contraseña definitiva" to finalPassword,
            "código de arranque" to bootstrap
        )
        secrets.forEach { (what, value) ->
            val leaks = linesContaining(value)
            assertFalse(leaks.isNotEmpty(), "the $what reached the logs at DEBUG:\n${leaks.joinToString("\n")}")
        }
    }

    /** The quiet route: debuggers, log statements and stack traces all reach for it. */
    @Test
    fun `the account entity toString carries neither the email nor the hash`() {
        val account = AccountEntity(
            employeeId = UUID.randomUUID(),
            email = "tostring@granatum.es",
            role = Role.EMPLOYEE,
            passwordHash = "{argon2}\$argon2id\$v=19\$m=1024,t=1,p=1\$sal\$hash"
        )

        assertFalse(account.toString().contains("tostring@granatum.es"))
        assertFalse(account.toString().contains("argon2"))
    }
}
