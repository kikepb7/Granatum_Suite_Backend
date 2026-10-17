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
 * `log.debug("exportando {}", person.name)` in `RegisterExportService` turned it
 * red with the offending line quoted; restored afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoPersonalDataInExportIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var dataSource: DataSource

    private val client by lazy { RestClient.builder().baseUrl("http://localhost:$port").build() }
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

    @AfterEach
    fun releaseLogs() {
        root.detachAppender(appender)
        appender.stop()
        root.level = previousLevel ?: Level.INFO
    }

    private val name = "Nombreunico ${UUID.randomUUID().toString().take(6)}"
    private val document = "Q${(10_000_000..99_999_999).random()}X"
    private val latitude = "39.469907"
    private val longitude = "-0.376288"

    private fun seed(): UUID {
        val employee = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO employees (id, name, identity_document, position, contract_type,
                                       hire_date, active, created_at, updated_at)
                VALUES (?, ?, ?, 'Florista', 'PART_TIME', '2020-01-01', TRUE, now(), now())
                """.trimIndent()
            ).use { s ->
                s.setObject(1, employee); s.setString(2, name); s.setString(3, document); s.executeUpdate()
            }
            c.prepareStatement(
                """
                INSERT INTO shifts (id, employee_id, clock_in, clock_out, status, worked_minutes,
                                    was_incomplete, clock_in_latitude, clock_in_longitude,
                                    created_at, updated_at)
                VALUES (?, ?, TIMESTAMPTZ '2025-02-11 08:00:00+01', TIMESTAMPTZ '2025-02-11 14:00:00+01',
                        'CLOSED', 360, FALSE, ?::numeric, ?::numeric, now(), now())
                """.trimIndent()
            ).use { s ->
                s.setObject(1, UUID.randomUUID()); s.setObject(2, employee)
                s.setString(3, latitude); s.setString(4, longitude); s.executeUpdate()
            }
        }
        return employee
    }

    private fun download(path: String): ByteArray =
        client.get().uri(path)
            .header("Authorization", "Bearer ${jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)}")
            .exchange({ _, r -> assertEquals(200, r.statusCode.value()); r.body.readAllBytes() }, false)!!

    @Test
    fun `exporting, downloading a month and verifying leave no name, document or coordinate in the logs`() {
        val employee = seed()

        appender.start()
        root.addAppender(appender)
        previousLevel = root.level
        root.level = Level.DEBUG

        val staff = download("/api/shifts/export?from=2025-02-11&to=2025-02-11")
        val monthly = download("/api/shifts/employee/$employee/summary/download?year=2025&month=2")
        val verification = client.post().uri("/api/exports/verify")
            .header("Authorization", "Bearer ${jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)}")
            .contentType(MediaType("text", "csv"))
            .body(staff)
            .exchange({ _, r -> r.statusCode.value() }, false)

        // The calls did carry the data: otherwise a clean log would prove nothing.
        assertTrue(String(staff, Charsets.UTF_8).contains(document))
        assertTrue(String(monthly, Charsets.UTF_8).contains(name))
        assertEquals(200, verification)
        assertTrue(captured().isNotEmpty(), "DEBUG was on and something was logged")

        val fromServer = captured().filterNot { it.loggerName.startsWith("org.springframework.web.client") }
        val leaks = listOf(name, document, latitude, longitude).flatMap { datum ->
            fromServer
                .filter { (it.formattedMessage + (it.throwableProxy?.message ?: "")).contains(datum) }
                .map { "[$datum] [${it.loggerName}] ${it.formattedMessage.take(200)}" }
        }
        assertEquals(emptyList(), leaks, "personal data reached the logs")
    }
}
