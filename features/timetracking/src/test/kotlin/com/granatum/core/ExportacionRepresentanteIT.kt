package com.granatum.core

import com.granatum.core.domain.model.AlcanceExportacion
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * US4: what the workers' representatives receive (FR-007, FR-012, FR-023;
 * SC-004).
 *
 * Article 34.9 entitles them to the register, not to each person's identity
 * document or whereabouts. The document column is **omitted**, not blanked; the
 * location is never exported, for any role.
 *
 * Checked on every path that writes a file - the range export for a person, for
 * the staff, and the monthly download. The monthly one goes through a different
 * service method, so testing only the staff would let a leak there through
 * (finding H1 of the analysis).
 */
@SpringBootTest(classes = [TimetrackingTestApplication::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportacionRepresentanteIT {

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

    private val documentos = listOf("REPDOC111A", "REPDOC222B")
    // Distinctive enough that no date, time or minute count can contain them.
    private val latitudes = listOf("40.416775", "41.387917")
    private val longitudes = listOf("-3.703790", "2.169919")

    private lateinit var personas: List<EmpleadoEntity>

    @BeforeAll
    fun sembrar() {
        personas = documentos.mapIndexed { i, doc ->
            semilla.empleado(nombre = "Persona $i", documento = doc).also { p ->
                semilla.fichaje(p, "2025-06-02 08:00", "2025-06-02 14:00", ubicacion = latitudes[i] to longitudes[i])
            }
        }
    }

    private fun exportar(rol: Role, alcance: AlcanceExportacion): ByteArray {
        val out = ByteArrayOutputStream()
        exportacion.exportar(alcance, desde, hasta, UUID.randomUUID(), rol, out)
        return out.toByteArray()
    }

    private fun mensual(rol: Role): ByteArray {
        val out = ByteArrayOutputStream()
        exportacion.descargarMensual(personas[0].id, 2025, 6, UUID.randomUUID(), rol, out)
        return out.toByteArray()
    }

    private fun sinDocumento(bytes: ByteArray, caso: String) {
        val texto = String(bytes, Charsets.UTF_8)
        assertFalse("Documento" in LectorCsv.cabecera(bytes), "$caso: the column is omitted, not blanked")
        documentos.forEach { assertFalse(texto.contains(it), "$caso: document $it must not appear") }
        val anchura = LectorCsv.cabecera(bytes).size
        LectorCsv.registros(bytes).filter { it != listOf("") }.forEach {
            assertEquals(anchura, it.size, "$caso: every row as wide as the header")
        }
    }

    private fun sinUbicacion(bytes: ByteArray, caso: String) {
        val texto = String(bytes, Charsets.UTF_8)
        (latitudes + longitudes).forEach { assertFalse(texto.contains(it), "$caso: coordinate $it must not appear") }
    }

    /** FR-012, SC-004 */
    @Test
    fun `REPRESENTANTE gets no document column, on every path`() {
        sinDocumento(exportar(Role.REPRESENTANTE, AlcanceExportacion.Plantilla), "staff")
        sinDocumento(exportar(Role.REPRESENTANTE, AlcanceExportacion.Persona(personas[0].id)), "person")
        sinDocumento(mensual(Role.REPRESENTANTE), "monthly")
        assertEquals("Persona 0", LectorCsv.filas(mensual(Role.REPRESENTANTE)).single()["Persona"], "the register itself is there")
    }

    /** FR-007: no location, for anyone. */
    @Test
    fun `no coordinate leaves for any role, on every path`() {
        listOf(Role.REPRESENTANTE, Role.ADMIN, Role.ENCARGADO).forEach { rol ->
            sinUbicacion(exportar(rol, AlcanceExportacion.Plantilla), "$rol staff")
            sinUbicacion(exportar(rol, AlcanceExportacion.Persona(personas[1].id)), "$rol person")
            sinUbicacion(mensual(rol), "$rol monthly")
        }
    }

    @Test
    fun `the other roles do get the document`() {
        val bytes = exportar(Role.ADMIN, AlcanceExportacion.Plantilla)
        assertTrue("Documento" in LectorCsv.cabecera(bytes))
        assertEquals(documentos, LectorCsv.filas(bytes).map { it["Documento"] })
    }

    @Test
    fun `exporting as REPRESENTANTE writes nothing to the register, and is recorded with that role`() {
        val antes = semilla.huellaDelRegistro()
        exportar(Role.REPRESENTANTE, AlcanceExportacion.Persona(personas[0].id))
        mensual(Role.REPRESENTANTE)
        assertEquals(antes, semilla.huellaDelRegistro())

        val ultimas = exportaciones.buscar(personas[0].id, null, null, null, null).take(2)
        assertTrue(ultimas.all { it.rolSolicitante == Role.REPRESENTANTE }, "D-012: the log says which version was handed over")
    }
}
