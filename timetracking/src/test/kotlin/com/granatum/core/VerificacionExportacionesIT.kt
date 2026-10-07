package com.granatum.core

import com.granatum.core.domain.exception.FicheroDemasiadoGrandeException
import com.granatum.core.domain.model.AlcanceExportacion
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.service.ExportacionService
import com.granatum.core.service.VerificacionExportaciones
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FR-028, SC-008: a file can be recognised by its content alone, and a single
 * changed character is enough for it not to be.
 *
 * The file is read as a stream and only hashed (D-007): nothing of it is kept,
 * and a body beyond the configured size is refused without reading the rest.
 * The test configuration caps it at 1 MiB.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class VerificacionExportacionesIT {

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
    @Autowired lateinit var verificacion: VerificacionExportaciones
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var dataSource: DataSource

    private val semilla by lazy { SemillaRegistro(dataSource, empleadoRepository) }

    private fun exportarUno(): Pair<ByteArray, UUID> {
        val persona = semilla.empleado(nombre = "Persona Verificada")
        semilla.fichaje(persona, "2025-06-02 08:00", "2025-06-02 14:00")
        val out = ByteArrayOutputStream()
        val r = exportacion.exportar(
            AlcanceExportacion.Persona(persona.id), LocalDate.parse("2025-06-01"), LocalDate.parse("2025-06-30"),
            persona.id, Role.EMPLEADO, out
        )
        return out.toByteArray() to r.exportacionId
    }

    @Test
    fun `a freshly exported file matches, and its export is returned`() {
        val (bytes, id) = exportarUno()
        val resultado = verificacion.verificar(ByteArrayInputStream(bytes))
        assertEquals(listOf(id), resultado.coincidencias.map { it.id })
    }

    @Test
    fun `two identical exports are both returned`() {
        val persona = semilla.empleado(nombre = "Dos Veces")
        semilla.fichaje(persona, "2025-06-02 08:00", "2025-06-02 14:00")
        fun exportar() = ByteArrayOutputStream().also {
            exportacion.exportar(
                AlcanceExportacion.Persona(persona.id), LocalDate.parse("2025-06-01"), LocalDate.parse("2025-06-30"),
                persona.id, Role.EMPLEADO, it
            )
        }.toByteArray()
        val primero = exportar()
        exportar()

        assertEquals(2, verificacion.verificar(ByteArrayInputStream(primero)).coincidencias.size)
    }

    /** SC-008: one character is enough. */
    @Test
    fun `the same file with one character changed does not match`() {
        val (bytes, _) = exportarUno()
        val alterado = bytes.copyOf()
        val i = String(bytes, Charsets.UTF_8).indexOf("14:00")
        alterado[i + 1] = '5'.code.toByte()   // 14:00 -> 15:00

        assertEquals(emptyList(), verificacion.verificar(ByteArrayInputStream(alterado)).coincidencias)
    }

    @Test
    fun `a body beyond the limit is refused without reading all of it`() {
        val limite = 1_048_576L
        var leidos = 0L
        val enorme = object : InputStream() {
            override fun read(): Int = if (leidos++ < limite * 10) 'x'.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (leidos >= limite * 10) return -1
                b.fill('x'.code.toByte(), off, off + len)
                leidos += len
                return len
            }
        }

        assertFailsWith<FicheroDemasiadoGrandeException> { verificacion.verificar(enorme) }
        assertTrue(leidos < limite * 2, "stops shortly after the limit, read $leidos bytes")
    }

    /** D-007: verification keeps nothing - no table grows but the fingerprint lookup reads. */
    @Test
    fun `verifying stores nothing`() {
        val (bytes, _) = exportarUno()
        val antes = semilla.huellaDelRegistro() + ("exportaciones" to contarExportaciones())
        verificacion.verificar(ByteArrayInputStream(bytes))
        verificacion.verificar(ByteArrayInputStream("cualquier cosa".toByteArray()))
        assertEquals(antes, semilla.huellaDelRegistro() + ("exportaciones" to contarExportaciones()))
    }

    private fun contarExportaciones(): String =
        dataSource.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM exportaciones").use { r -> r.next(); r.getString(1) } }
        }
}
