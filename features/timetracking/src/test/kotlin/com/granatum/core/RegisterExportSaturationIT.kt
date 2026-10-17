package com.granatum.core

import com.granatum.core.domain.exception.ExportBusyException
import com.granatum.core.domain.model.ExportTarget
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.service.RegisterExportService
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
 * at once with [ExportBusyException] (`503` + `Retry-After`) instead
 * of queueing for a connection. And the permit comes back however the export
 * ends: a semaphore that leaks permits is a permanent outage, worse than the
 * one it prevents.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class RegisterExportSaturationIT {

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
            registry.add("timetracking.export.concurrency") { "1" }
            registry.add("timetracking.export.wait-ms") { "200" }
        }
    }

    @Autowired lateinit var exportService: RegisterExportService
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var dataSource: DataSource

    private val from = LocalDate.parse("2025-06-01")
    private val to = LocalDate.parse("2025-06-30")

    @Test
    fun `a second export while one is writing is refused, and the permit comes back`() {
        val person = RegisterSeed(dataSource, employeeRepository).employee()
        val writing = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        val held = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                writing.countDown()
                releaseLatch.await(30, TimeUnit.SECONDS)
            }
        }
        fun export(clockOut: OutputStream) =
            exportService.export(ExportTarget.Person(person.id), from, to, person.id, Role.EMPLOYEE, clockOut)

        val thread = Executors.newSingleThreadExecutor()
        try {
            val firstResponse = thread.submit { export(held) }
            writing.await(30, TimeUnit.SECONDS)

            assertFailsWith<ExportBusyException> { export(ByteArrayOutputStream()) }

            releaseLatch.countDown()
            firstResponse.get(30, TimeUnit.SECONDS)
        } finally {
            releaseLatch.countDown()
            thread.shutdownNow()
        }

        // Released after a normal end...
        export(ByteArrayOutputStream())

        // ...and after a failure.
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("Broken pipe")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("Broken pipe")
        }
        assertFailsWith<IOException> { export(broken) }
        assertEquals(1, RegisterCsvReader.records(ByteArrayOutputStream().also { export(it) }.toByteArray()).size)
    }

    /**
     * The HTTP path takes the permit in [RegisterExportService.prepare], on the
     * request thread, so that saturation is still a `503` and not a broken
     * `200`. A preparation that is refused must not keep the permit.
     */
    @Test
    fun `a refused preparation does not keep the permit`() {
        val person = RegisterSeed(dataSource, employeeRepository).employee()
        assertFailsWith<com.granatum.core.domain.exception.InconsistentValuesException> {
            exportService.prepare(ExportTarget.Person(person.id), to, from, person.id, Role.EMPLOYEE)
        }
        exportService.export(ExportTarget.Person(person.id), from, to, person.id, Role.EMPLOYEE, ByteArrayOutputStream())
    }
}
