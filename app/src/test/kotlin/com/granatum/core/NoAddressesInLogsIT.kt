package com.granatum.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-006, SC-008: the client's address is personal data and must not reach the
 * logs - not when a request is let through, not when it is limited, and not at
 * DEBUG, which is where a diagnostic session in production runs.
 *
 * Behind a proxy (`native`), the address arrives in a header and Tomcat's
 * RemoteIpValve rewrites it, so that is the configuration under test: it adds
 * one more component that could log it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["server.forward-headers-strategy=native", "security.rate-limits.login=1/1m"])
class NoAddressesInLogsIT {

    @LocalServerPort
    var port: Int = 0

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

    private fun login(from: String): Int =
        RestClient.create("http://localhost:$port").post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", from)
            .body("""{"email":"nobody@granatum.es","password":"wrong"}""")
            .exchange({ _, r -> r.statusCode.value() }, false)!!

    @Test
    fun `neither an allowed nor a limited request writes the client's address`() {
        val address = "203.0.113.77"

        assertEquals(401, login(address))
        assertEquals(429, login(address))

        assertTrue(captured().isNotEmpty(), "the appender must have captured something")
        val leaks = captured()
            // The test's own HTTP client logs what it sends; it is not the server.
            .filterNot { it.loggerName.startsWith("org.springframework.web.client.") }
            .filter { it.formattedMessage.contains(address) }
            .map { "[${it.loggerName}] ${it.formattedMessage.take(200)}" }
        assertTrue(leaks.isEmpty(), "the client's address reached the logs at DEBUG:\n${leaks.joinToString("\n")}")
    }
}
