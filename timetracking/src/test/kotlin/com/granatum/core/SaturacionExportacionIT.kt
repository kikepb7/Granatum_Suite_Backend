package com.granatum.core

import com.granatum.core.domain.exception.ExportacionSaturadaException
import com.granatum.core.domain.model.AlcanceExportacion
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.service.ExportacionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * D-002: exports in flight are capped, because each one holds a database
 * connection for as long as its client takes to read.
 *
 * With one permit, a second export while the first is still writing is refused
 * at once with [ExportacionSaturadaException] (`503` + `Retry-After`) instead
 * of queueing for a connection. And the permit comes back however the export
 * ends: a semaphore that leaks permits is a permanent outage, worse than the
 * one it prevents.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class SaturacionExportacionIT {

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
            registry.add("timetracking.exportacion.concurrencia") { "1" }
            registry.add("timetracking.exportacion.espera-ms") { "200" }
        }
    }

    @Autowired lateinit var exportacion: ExportacionService
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var dataSource: DataSource

    private val desde = LocalDate.parse("2025-06-01")
    private val hasta = LocalDate.parse("2025-06-30")

    @Test
    fun `a second export while one is writing is refused, and the permit comes back`() {
        val persona = SemillaRegistro(dataSource, empleadoRepository).empleado()
        val escribiendo = CountDownLatch(1)
        val soltar = CountDownLatch(1)
        val retenida = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                escribiendo.countDown()
                soltar.await(30, TimeUnit.SECONDS)
            }
        }
        fun exportar(salida: OutputStream) =
            exportacion.exportar(AlcanceExportacion.Persona(persona.id), desde, hasta, persona.id, Role.EMPLEADO, salida)

        val hilo = Executors.newSingleThreadExecutor()
        try {
            val primera = hilo.submit { exportar(retenida) }
            escribiendo.await(30, TimeUnit.SECONDS)

            assertFailsWith<ExportacionSaturadaException> { exportar(ByteArrayOutputStream()) }

            soltar.countDown()
            primera.get(30, TimeUnit.SECONDS)
        } finally {
            soltar.countDown()
            hilo.shutdownNow()
        }

        // Released after a normal end...
        exportar(ByteArrayOutputStream())

        // ...and after a failure.
        val rota = object : OutputStream() {
            override fun write(b: Int) = throw IOException("Broken pipe")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("Broken pipe")
        }
        assertFailsWith<IOException> { exportar(rota) }
        assertEquals(1, LectorCsv.registros(ByteArrayOutputStream().also { exportar(it) }.toByteArray()).size)
    }

    /**
     * The HTTP path takes the permit in [ExportacionService.preparar], on the
     * request thread, so that saturation is still a `503` and not a broken
     * `200`. A preparation that is refused must not keep the permit.
     */
    @Test
    fun `a refused preparation does not keep the permit`() {
        val persona = SemillaRegistro(dataSource, empleadoRepository).empleado()
        assertFailsWith<com.granatum.core.domain.exception.ValoresIncoherentesException> {
            exportacion.preparar(AlcanceExportacion.Persona(persona.id), hasta, desde, persona.id, Role.EMPLEADO)
        }
        exportacion.exportar(AlcanceExportacion.Persona(persona.id), desde, hasta, persona.id, Role.EMPLEADO, ByteArrayOutputStream())
    }
}
