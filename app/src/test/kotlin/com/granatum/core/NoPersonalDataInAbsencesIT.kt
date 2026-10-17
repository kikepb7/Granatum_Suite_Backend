package com.granatum.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.granatum.core.HttpTestClient.Companion.field
import com.granatum.core.HttpTestClient.Companion.validDni
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
class NoPersonalDataInAbsencesIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var jwtService: JwtService

    private val http by lazy { HttpTestClient(port) }
    private val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    /**
     * A snapshot of what was captured. Logback appends to the list while
     * holding the appender's lock, and scheduled jobs or server threads may
     * still be logging while the test reads: iterating the live list then
     * throws ConcurrentModificationException (seen once in a full build).
     */
    private fun captured(): List<ILoggingEvent> = synchronized(appender) { appender.list.toList() }
    private var previousLevel: Level? = null

    @BeforeEach
    fun capture() {
        previousLevel = root.level
        appender.start()
        root.addAppender(appender)
        root.level = Level.DEBUG
    }

    @AfterEach
    fun release() {
        root.detachAppender(appender)
        appender.stop()
        root.level = previousLevel ?: Level.INFO
    }

    @Test
    fun `comments and rejection reasons stay out of the logs`() {
        val admin = jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)
        val created = http.post(
            "/api/employees",
            """{"name":"Persona Ausencias","identityDocument":"${validDni()}","position":"Florista","contractType":"PART_TIME","hireDate":"2026-01-01"}""",
            admin
        )
        assertEquals(201, created.status, created.body)
        val employee = jwtService.generateAccessToken(UUID.fromString(field(created.body, "id")), Role.EMPLOYEE)
        val manager = jwtService.generateAccessToken(UUID.randomUUID(), Role.MANAGER)

        val comment = "Boda-de-mi-hermana-${UUID.randomUUID().toString().take(8)}"
        val reason = "Inventario-anual-${UUID.randomUUID().toString().take(8)}"
        val year = LocalDate.now().year + 1

        val requested = http.post(
            "/api/absences",
            """{"type":"VACATION","from":"$year-06-01","to":"$year-06-03","comment":"$comment"}""",
            employee
        )
        assertEquals(201, requested.status, requested.body)
        val rejected = http.post("/api/absences/${field(requested.body, "id")}/reject", """{"reason":"$reason"}""", manager)
        assertEquals(200, rejected.status, rejected.body)

        assertTrue(captured().isNotEmpty())
        mapOf("comment" to comment, "rejection reason" to reason).forEach { (what, value) ->
            val leaks = captured()
                .filterNot { it.loggerName.startsWith("org.springframework.web.client.") }
                .filter { it.formattedMessage.contains(value) }
                .map { "[${it.loggerName}] ${it.formattedMessage.take(200)}" }
            assertTrue(leaks.isEmpty(), "the $what reached the logs at DEBUG:\n${leaks.joinToString("\n")}")
        }
    }
}
