package com.granatum.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.granatum.core.domain.exception.DuplicateDocumentException
import com.granatum.core.domain.type.ContractType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.scheduling.IncompleteShiftMarkingJob
import com.granatum.core.service.EmployeeService
import com.granatum.core.service.ShiftService
import com.granatum.core.service.ShiftLocationInput
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Principle VI, as a test rather than a review.
 *
 * `/speckit-analyze` flagged that this was originally a manual audit task,
 * which satisfies neither half of principle V: the invariants of principles
 * III, IV, VI and VII must have a test that fails if the rule is broken,
 * because "a rule without a test is an intention, not a guarantee".
 *
 * It earned its place immediately. It found that Hibernate's `EntityPrinter`
 * dumps every persistent property of an entity **by reflection**, bypassing the
 * `toString()` on `EmployeeEntity` that deliberately omits the identity
 * document. The fix was not in the entity but in the logging configuration, and
 * the root logger is raised to DEBUG here on purpose: that is exactly the state
 * a production diagnostic session puts the application in.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class NoPersonalDataInLogsIT {

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
            registry.add("timetracking.incomplete.cron") { "0 0 4 1 1 *" }
            registry.add("timetracking.retention.cron") { "0 0 4 1 1 *" }
        }

        /**
         * A distinct document per test. These tests share one database and the
         * services commit, so a fixed value would make the first test's employee
         * collide with every later one.
         */
        private val counter = AtomicInteger(0)
    }

    @Autowired lateinit var employeeService: EmployeeService
    @Autowired lateinit var shiftService: ShiftService
    @Autowired lateinit var job: IncompleteShiftMarkingJob

    private lateinit var appender: ListAppender<ILoggingEvent>

    /**
     * A snapshot of what was captured. Logback appends to the list while
     * holding the appender's lock, and scheduled jobs or server threads may
     * still be logging while the test reads: iterating the live list then
     * throws ConcurrentModificationException (seen once in a full build).
     */
    private fun capturedEvents(): List<ILoggingEvent> = synchronized(appender) { appender.list.toList() }
    private lateinit var root: Logger

    private val latitude = BigDecimal("37.123456")
    private val longitude = BigDecimal("-5.654321")

    private val madrid = com.granatum.core.api.util.CivilDateRange.ZONE

    private fun madrid(date: String, time: String) =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(madrid).toInstant()

    /** Distinctive enough to grep for, with a real check letter. */
    private fun uniqueDocument(): String {
        val number = 11_220_000 + counter.incrementAndGet()
        val letter = "TRWAGMYFPDXBNJZSQVHLCKE"[number % 23]
        return "$number$letter"
    }

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

    private fun linesContaining(text: String): List<String> =
        capturedEvents()
            .filter { (it.formattedMessage + (it.throwableProxy?.message ?: "")).contains(text) }
            .map { "[${it.loggerName}] ${it.formattedMessage.take(160)}" }

    @Test
    fun `no log line carries the document number or the location`() {
        val document = uniqueDocument()

        val employee = employeeService.create(
            name = "Persona de prueba",
            identityDocument = document,
            position = "Florista",
            contractType = ContractType.FULL_TIME,
            hireDate = LocalDate.parse("2026-10-01")
        )

        val location = ShiftLocationInput(latitude, longitude, 12)
        val f = shiftService.clockIn(
            employee.id, madrid("2026-10-05", "07:00"), location
        )
        shiftService.clockOut(
            f.id, employee.id, madrid("2026-10-05", "16:00"), location
        )

        val withDocument = linesContaining(document)
        assertFalse(
            withDocument.isNotEmpty(),
            "the identity document reached the logs, which is where personal data " +
                "escapes unnoticed:\n${withDocument.joinToString("\n")}"
        )

        val withLocation = linesContaining(latitude.toPlainString()) +
            linesContaining(longitude.toPlainString())
        assertFalse(
            withLocation.isNotEmpty(),
            "the location reached the logs - the most intrusive datum in the " +
                "register:\n${withLocation.joinToString("\n")}"
        )
    }

    @Test
    fun `an error message about a duplicate document does not quote the document`() {
        val document = uniqueDocument()

        employeeService.create(
            "Primera", document, "Florista",
            ContractType.FULL_TIME, LocalDate.parse("2026-10-01")
        )

        val error = assertFailsWith<DuplicateDocumentException> {
            employeeService.create(
                "Segunda", document, "Montador",
                ContractType.PART_TIME, LocalDate.parse("2026-10-02")
            )
        }

        assertFalse(
            error.message.contains(document),
            "the message reaches the client and the logs, so it must name the field " +
                "and not its value: ${error.message}"
        )
    }

    /**
     * `toString` is the quiet route: a debugger, a log statement and a stack
     * trace all reach for it.
     */
    @Test
    fun `entity toString carries neither the document nor the location`() {
        val document = uniqueDocument()

        val entity = EmployeeEntity(
            name = "Persona de prueba",
            identityDocument = document,
            position = "Florista",
            contractType = ContractType.FULL_TIME,
            hireDate = LocalDate.parse("2026-10-01"),
            active = true
        )

        assertFalse(
            entity.toString().contains(document),
            "EmployeeEntity.toString leaks the document: $entity"
        )
    }

    /**
     * The daily job logs counts only. An identifier there would write a person's
     * working pattern into the log every single night.
     */
    @Test
    fun `the scheduled job logs counts and not identifiers`() {
        val document = uniqueDocument()
        val employee = employeeService.create(
            "Persona", document, "Florista",
            ContractType.FULL_TIME, LocalDate.parse("2026-10-01")
        )
        val yesterday = LocalDate.now(madrid).minusDays(1)
        shiftService.clockIn(
            employee.id,
            LocalDateTime.of(yesterday, LocalTime.parse("08:00")).atZone(madrid).toInstant(),
            null
        )

        synchronized(appender) { appender.list.clear() }
        job.markIncomplete()

        val withId = linesContaining(employee.id.toString())
        assertFalse(
            withId.isNotEmpty(),
            "the job must log how many, never who:\n${withId.joinToString("\n")}"
        )
        assertFalse(linesContaining(document).isNotEmpty())
    }
}
