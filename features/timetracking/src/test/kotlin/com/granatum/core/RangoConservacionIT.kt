package com.granatum.core

import com.granatum.core.domain.exception.ValoresIncoherentesException
import com.granatum.core.domain.model.AlcanceExportacion
import com.granatum.core.domain.model.RangoEfectivo
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.service.ExportacionService
import com.granatum.core.service.PlazoConservacion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayOutputStream
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * FR-017, D-009: a range reaching past the retention period is trimmed to the
 * boundary, and says so, so that missing rows are not read as days not worked.
 *
 * The boundary is [PlazoConservacion], the same one the purge uses: the export
 * never announces data the purge has removed, nor hides data it has kept.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class RangoConservacionIT {

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
        }
    }

    @Autowired lateinit var exportacion: ExportacionService
    @Autowired lateinit var plazo: PlazoConservacion
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var dataSource: DataSource

    private val semilla by lazy { SemillaRegistro(dataSource, empleadoRepository) }

    @Test
    fun `a range starting before the boundary is trimmed to it and marked`() {
        val persona = semilla.empleado()
        val corte = plazo.fechaCorte()
        // Older than the period, and still there because the purge is off by
        // default: it must not come out as if the period did not exist.
        val antiguo = corte.minusDays(10)
        semilla.fichaje(persona, "$antiguo 07:00", "$antiguo 15:00")
        semilla.fichaje(persona, "$corte 07:00", "$corte 15:00")

        val out = ByteArrayOutputStream()
        val resultado = exportacion.exportar(
            AlcanceExportacion.Persona(persona.id), corte.minusYears(1), corte.plusDays(5),
            persona.id, Role.EMPLEADO, out
        )

        assertEquals(RangoEfectivo(corte, corte.plusDays(5), recortado = true), resultado.rango)
        assertEquals(listOf(corte.toString()), LectorCsv.filas(out.toByteArray()).map { it["Fecha"] })
    }

    @Test
    fun `a range inside the period is untouched`() {
        val persona = semilla.empleado()
        val desde = plazo.fechaCorte().plusDays(1)
        val hasta = desde.plusMonths(1)

        val resultado = exportacion.exportar(
            AlcanceExportacion.Persona(persona.id), desde, hasta, persona.id, Role.EMPLEADO, ByteArrayOutputStream()
        )

        assertEquals(RangoEfectivo(desde, hasta, recortado = false), resultado.rango)
    }

    /**
     * Nothing of the range is still within the period. An empty file would say
     * "no shifts", which is the very misreading FR-017 exists to prevent, so the
     * request is refused with the date data starts from.
     */
    @Test
    fun `a range entirely before the boundary is refused`() {
        val persona = semilla.empleado()
        val corte = plazo.fechaCorte()

        val error = assertFailsWith<ValoresIncoherentesException> {
            exportacion.exportar(
                AlcanceExportacion.Persona(persona.id), corte.minusYears(1), corte.minusDays(1),
                persona.id, Role.EMPLEADO, ByteArrayOutputStream()
            )
        }
        assertEquals(true, error.message?.contains(corte.toString()))
    }
}
