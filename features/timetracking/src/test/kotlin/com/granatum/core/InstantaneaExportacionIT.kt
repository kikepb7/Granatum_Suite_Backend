package com.granatum.core

import com.granatum.core.domain.model.AlcanceExportacion
import com.granatum.core.domain.model.ValoresFichaje
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.service.CorreccionService
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
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * D-002: the whole export sees the database as it was when it started.
 *
 * With one fichaje per batch, the test's output stream - on receiving the first
 * data row - approves, **from another thread** and waiting for its commit, a
 * correction on a fichaje the export has not read yet. The file must show that
 * fichaje without the correction. A file mixing states from before and after a
 * change would not be the register at any moment, and two exports "with no
 * change in between" would not be comparable (FR-011).
 *
 * ## Validated by mutation (2026-10-07)
 *
 * Both mutations of `ExportacionService` turn this test red, with the same
 * failure: `expected: <2025-03-04 15:00> but was: <2025-03-04 18:00>`.
 *
 * 1. **No single transaction**: `lectura.executeWithoutResult { ... }` replaced
 *    by `run { ... }`, so each query of each batch runs in its own transaction
 *    and sees the latest commit.
 * 2. **One transaction at the default isolation**: `ISOLATION_REPEATABLE_READ`
 *    replaced by `ISOLATION_READ_COMMITTED`. One transaction is not enough on
 *    its own: in Postgres, READ COMMITTED takes a new snapshot per statement,
 *    so the later batch still sees the approval.
 *
 * Restored after each run; the test is green against the real code.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class InstantaneaExportacionIT {

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
            // One fichaje per batch, so the second one is read by a later query.
            registry.add("timetracking.exportacion.tamano-lote") { "1" }
        }
    }

    @Autowired lateinit var exportacion: ExportacionService
    @Autowired lateinit var correcciones: CorreccionService
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var dataSource: DataSource

    private val semilla by lazy { SemillaRegistro(dataSource, empleadoRepository) }

    @Test
    fun `a correction approved mid-export does not reach the file`() {
        val persona = semilla.empleado()
        val encargado = semilla.empleado(nombre = "Encargado")
        semilla.fichaje(persona, "2025-03-03 07:00", "2025-03-03 15:00")
        val segundo = semilla.fichaje(persona, "2025-03-04 07:00", "2025-03-04 15:00")
        val solicitud = correcciones.solicitar(
            segundo, persona.id, Role.EMPLEADO, "Sali mas tarde",
            ValoresFichaje(semilla.instante("2025-03-04 07:00"), semilla.instante("2025-03-04 18:00"), emptyList())
        )

        val otroHilo = Executors.newSingleThreadExecutor()
        var aprobada = false
        val salida = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                super.write(b, off, len)
                // Header plus the first data row: the second fichaje is still unread.
                if (!aprobada && toString(Charsets.UTF_8).split("\r\n").size > 2) {
                    aprobada = true
                    otroHilo.submit { correcciones.aprobar(solicitud.id, encargado.id, Role.ENCARGADO) }
                        .get(30, TimeUnit.SECONDS)
                }
            }
        }

        try {
            exportacion.exportar(
                AlcanceExportacion.Persona(persona.id),
                LocalDate.parse("2025-03-01"), LocalDate.parse("2025-03-31"),
                persona.id, Role.EMPLEADO, salida
            )
        } finally {
            otroHilo.shutdownNow()
        }

        assertEquals(true, aprobada, "the approval must have happened during the export")
        val fila = LectorCsv.filas(salida.toByteArray()).single { it["Fecha"] == "2025-03-04" }
        assertEquals("2025-03-04 15:00", fila["Salida"], "the snapshot from the start, not the approved value")
        assertEquals("No", fila["Corregido"])
        assertEquals("", fila["Correcciones"])

        // And the correction did commit: a new export sees it.
        val despues = ByteArrayOutputStream()
        exportacion.exportar(
            AlcanceExportacion.Persona(persona.id),
            LocalDate.parse("2025-03-01"), LocalDate.parse("2025-03-31"),
            persona.id, Role.EMPLEADO, despues
        )
        assertEquals("2025-03-04 18:00", LectorCsv.filas(despues.toByteArray()).single { it["Fecha"] == "2025-03-04" }["Salida"])
    }
}
