package com.granatum.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.granatum.core.domain.exception.DocumentoDuplicadoException
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.scheduling.MarcadoFichajesIncompletosJob
import com.granatum.core.service.EmpleadoService
import com.granatum.core.service.FichajeService
import com.granatum.core.service.UbicacionInput
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
 * `toString()` on `EmpleadoEntity` that deliberately omits the identity
 * document. The fix was not in the entity but in the logging configuration, and
 * the root logger is raised to DEBUG here on purpose: that is exactly the state
 * a production diagnostic session puts the application in.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class SinDatosPersonalesEnLogsIT {

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
        }

        /**
         * A distinct document per test. These tests share one database and the
         * services commit, so a fixed value would make the first test's employee
         * collide with every later one.
         */
        private val contador = AtomicInteger(0)
    }

    @Autowired lateinit var empleadoService: EmpleadoService
    @Autowired lateinit var fichajeService: FichajeService
    @Autowired lateinit var job: MarcadoFichajesIncompletosJob

    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var raiz: Logger

    private val latitud = BigDecimal("37.123456")
    private val longitud = BigDecimal("-5.654321")

    private val madrid = com.granatum.core.api.util.RangoFechas.ZONA

    private fun madrid(fecha: String, hora: String) =
        LocalDateTime.of(LocalDate.parse(fecha), LocalTime.parse(hora)).atZone(madrid).toInstant()

    /** Distinctive enough to grep for, with a real check letter. */
    private fun documentoUnico(): String {
        val numero = 11_220_000 + contador.incrementAndGet()
        val letra = "TRWAGMYFPDXBNJZSQVHLCKE"[numero % 23]
        return "$numero$letra"
    }

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

    private fun lineasQueContienen(texto: String): List<String> =
        appender.list
            .filter { (it.formattedMessage + (it.throwableProxy?.message ?: "")).contains(texto) }
            .map { "[${it.loggerName}] ${it.formattedMessage.take(160)}" }

    @Test
    fun `no log line carries the document number or the location`() {
        val documento = documentoUnico()

        val empleado = empleadoService.crear(
            nombre = "Persona de prueba",
            documentoIdentidad = documento,
            puesto = "Florista",
            tipoContrato = TipoContrato.JORNADA_COMPLETA,
            fechaAlta = LocalDate.parse("2026-10-01")
        )

        val ubicacion = UbicacionInput(latitud, longitud, 12)
        val f = fichajeService.registrarEntrada(
            empleado.id, madrid("2026-10-05", "07:00"), ubicacion
        )
        fichajeService.registrarSalida(
            f.id, empleado.id, madrid("2026-10-05", "16:00"), ubicacion
        )

        val conDocumento = lineasQueContienen(documento)
        assertFalse(
            conDocumento.isNotEmpty(),
            "the identity document reached the logs, which is where personal data " +
                "escapes unnoticed:\n${conDocumento.joinToString("\n")}"
        )

        val conUbicacion = lineasQueContienen(latitud.toPlainString()) +
            lineasQueContienen(longitud.toPlainString())
        assertFalse(
            conUbicacion.isNotEmpty(),
            "the location reached the logs - the most intrusive datum in the " +
                "register:\n${conUbicacion.joinToString("\n")}"
        )
    }

    @Test
    fun `an error message about a duplicate document does not quote the document`() {
        val documento = documentoUnico()

        empleadoService.crear(
            "Primera", documento, "Florista",
            TipoContrato.JORNADA_COMPLETA, LocalDate.parse("2026-10-01")
        )

        val error = assertFailsWith<DocumentoDuplicadoException> {
            empleadoService.crear(
                "Segunda", documento, "Montador",
                TipoContrato.PARCIAL, LocalDate.parse("2026-10-02")
            )
        }

        assertFalse(
            error.message.contains(documento),
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
        val documento = documentoUnico()

        val entidad = EmpleadoEntity(
            nombre = "Persona de prueba",
            documentoIdentidad = documento,
            puesto = "Florista",
            tipoContrato = TipoContrato.JORNADA_COMPLETA,
            fechaAlta = LocalDate.parse("2026-10-01"),
            activo = true
        )

        assertFalse(
            entidad.toString().contains(documento),
            "EmpleadoEntity.toString leaks the document: $entidad"
        )
    }

    /**
     * The daily job logs counts only. An identifier there would write a person's
     * working pattern into the log every single night.
     */
    @Test
    fun `the scheduled job logs counts and not identifiers`() {
        val documento = documentoUnico()
        val empleado = empleadoService.crear(
            "Persona", documento, "Florista",
            TipoContrato.JORNADA_COMPLETA, LocalDate.parse("2026-10-01")
        )
        val ayer = LocalDate.now(madrid).minusDays(1)
        fichajeService.registrarEntrada(
            empleado.id,
            LocalDateTime.of(ayer, LocalTime.parse("08:00")).atZone(madrid).toInstant(),
            null
        )

        appender.list.clear()
        job.marcarIncompletos()

        val conId = lineasQueContienen(empleado.id.toString())
        assertFalse(
            conId.isNotEmpty(),
            "the job must log how many, never who:\n${conId.joinToString("\n")}"
        )
        assertFalse(lineasQueContienen(documento).isNotEmpty())
    }
}
