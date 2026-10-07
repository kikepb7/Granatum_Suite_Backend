package com.granatum.core

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
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * FR-011, SC-008: the same scope and range, with nothing changed in between,
 * give the same file byte for byte - and so the same fingerprint, which is
 * what lets a file be recognised later.
 *
 * ## Validated by mutation (2026-10-07)
 *
 * Each mutation of `ExportacionService` turned this class red, and was then
 * restored:
 *
 * - **(a) No tie-break by id**: `.thenBy { it.id.toString() }` removed from the
 *   staff ordering. The same-name test failed with
 *   `expected: <[DOC-MISMO-1, DOC-MISMO-0]> but was: <[DOC-MISMO-0, DOC-MISMO-1]>`:
 *   without the tie-break the order is whatever the database returns.
 * - **(b) Generation time inside the file**: a `Generado;<instant>` line written
 *   after the header. Both tests failed with `Array elements differ at index
 *   227` - the first byte of the timestamp.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class DeterminismoExportacionIT {

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
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var dataSource: DataSource

    private val semilla by lazy { SemillaRegistro(dataSource, empleadoRepository) }
    private val desde = LocalDate.parse("2025-06-01")
    private val hasta = LocalDate.parse("2025-06-30")

    private fun exportar(): Pair<ByteArray, String> {
        val out = ByteArrayOutputStream()
        val r = exportacion.exportar(AlcanceExportacion.Plantilla, desde, hasta, UUID.randomUUID(), Role.ADMIN, out)
        return out.toByteArray() to r.huella
    }

    @Test
    fun `two exports with nothing changed in between are identical`() {
        listOf("Marta", "Luis", "Ana").forEach { nombre ->
            val p = semilla.empleado(nombre = nombre)
            semilla.fichaje(p, "2025-06-02 08:00", "2025-06-02 14:00")
            semilla.fichaje(p, "2025-06-03 08:00", "2025-06-03 14:00")
        }

        val (primero, huella1) = exportar()
        val (segundo, huella2) = exportar()

        assertContentEquals(primero, segundo, "no generation time or any other moving part inside the file")
        assertEquals(huella1, huella2)
    }

    /**
     * Two people with the same name: the tie is broken by id, whatever order
     * they were stored in. They are inserted larger id first, so an order that
     * merely followed the database would come out reversed.
     */
    @Test
    fun `people with the same name are ordered by id, not by how they were stored`() {
        val ids = listOf(UUID.fromString("ffffffff-0000-0000-0000-000000000001"), UUID.fromString("00000000-0000-0000-0000-000000000002"))
        ids.forEachIndexed { i, id ->
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO empleados (id, nombre, documento_identidad, puesto, tipo_contrato,
                                           fecha_alta, activo, created_at, updated_at)
                    VALUES (?, 'Mismo Nombre', ?, 'Florista', 'JORNADA_COMPLETA', '2020-01-01', TRUE, now(), now())
                    """.trimIndent()
                ).use { s ->
                    s.setObject(1, id)
                    s.setString(2, "DOC-MISMO-$i")
                    s.executeUpdate()
                }
            }
            semilla.fichaje(empleadoRepository.findById(id).get(), "2025-06-10 08:00", "2025-06-10 14:00")
        }

        val (bytes, _) = exportar()
        val documentos = LectorCsv.filas(bytes).filter { it["Persona"] == "Mismo Nombre" }.map { it["Documento"] }
        assertEquals(
            listOf("DOC-MISMO-1", "DOC-MISMO-0"),
            documentos,
            "00000000-… before ffffffff-…, although ffffffff-… was stored first"
        )
        assertContentEquals(bytes, exportar().first)
    }
}
