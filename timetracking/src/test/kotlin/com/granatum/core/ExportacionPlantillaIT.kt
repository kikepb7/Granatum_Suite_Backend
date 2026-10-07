package com.granatum.core

import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.model.AlcanceExportacion
import com.granatum.core.domain.type.AlcanceRegistro
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.infrastructure.database.repositories.ExportacionRepository
import com.granatum.core.service.ExportacionService
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * US3: the whole staff in one file (FR-014, FR-015; SC-011).
 *
 * Everyone with shifts in the range appears, including people who have left -
 * the duty to keep and hand over their register does not end with the
 * contract. Ordered by name the way a Spanish reader expects, then by id.
 */
@SpringBootTest(classes = [TimetrackingTestApplication::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportacionPlantillaIT {

    companion object {
        // Started here: with PER_CLASS the context is built before the
        // @Testcontainers extension would start it (see ExportacionPersonaIT).
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

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
    @Autowired lateinit var exportaciones: ExportacionRepository
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var dataSource: DataSource

    private val semilla by lazy { SemillaRegistro(dataSource, empleadoRepository) }
    private val desde = LocalDate.parse("2025-06-01")
    private val hasta = LocalDate.parse("2025-06-30")

    @BeforeAll
    fun sembrar() {
        // Inserted out of order on purpose.
        listOf("Zoe", "Pablo", "Óscar", "Ángel", "Nuria").forEach { nombre ->
            val p = semilla.empleado(nombre = nombre)
            semilla.fichaje(p, "2025-06-02 08:00", "2025-06-02 14:00")
        }
        // Left the company, still within the retention period (FR-015).
        val baja = semilla.empleado(nombre = "Bea Baja", activo = false)
        semilla.fichaje(baja, "2025-06-03 08:00", "2025-06-03 14:00")
        // No shift in the range: no row.
        val fuera = semilla.empleado(nombre = "Fuera Delrango")
        semilla.fichaje(fuera, "2025-05-30 08:00", "2025-05-30 14:00")
    }

    private fun exportar(rol: Role = Role.ADMIN, alcance: AlcanceExportacion = AlcanceExportacion.Plantilla): ByteArray {
        val out = ByteArrayOutputStream()
        exportacion.exportar(alcance, desde, hasta, UUID.randomUUID(), rol, out)
        return out.toByteArray()
    }

    @Test
    fun `everyone with shifts in the range appears, including someone who has left`() {
        val personas = LectorCsv.filas(exportar()).map { it["Persona"] }
        assertEquals(6, personas.size)
        assertEquals(true, "Bea Baja" in personas, "SC-011: leaving does not end the register")
        assertEquals(false, "Fuera Delrango" in personas)
    }

    /**
     * Spanish collation: accents sort with their letter, so `Ángel` comes
     * first and `Óscar` between `Nuria` and `Pablo`. A plain code-point sort
     * would put both after `Zoe`.
     */
    @Test
    fun `people are ordered by name with Spanish collation`() {
        assertEquals(
            listOf("Ángel", "Bea Baja", "Nuria", "Óscar", "Pablo", "Zoe"),
            LectorCsv.filas(exportar()).map { it["Persona"] }
        )
    }

    /** FR-014 */
    @Test
    fun `ENCARGADO, ADMIN and REPRESENTANTE may export the staff, EMPLEADO may not`() {
        listOf(Role.ENCARGADO, Role.ADMIN, Role.REPRESENTANTE).forEach { rol ->
            assertEquals(6, LectorCsv.filas(exportar(rol)).size, "$rol")
        }
        assertFailsWith<ForbiddenException> { exportar(Role.EMPLEADO) }
    }

    @Test
    fun `a staff export is recorded as PLANTILLA without a person`() {
        val antes = exportaciones.buscar(null, null, null, null, null).count { it.alcance == AlcanceRegistro.PLANTILLA }
        exportar()
        val plantilla = exportaciones.buscar(null, null, null, null, null).filter { it.alcance == AlcanceRegistro.PLANTILLA }
        assertEquals(antes + 1, plantilla.size)
        assertNull(plantilla.first().empleadoId)
        assertEquals(6, plantilla.first().filas)
    }
}
