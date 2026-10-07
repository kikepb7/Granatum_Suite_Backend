package com.granatum.core

import com.granatum.core.domain.model.AlcanceExportacion
import com.granatum.core.domain.type.AlcanceRegistro
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.infrastructure.database.repositories.ExportacionRepository
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
import java.security.MessageDigest
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-025, FR-026, SC-007: every export leaves exactly one row, complete or
 * not, and the row holds nothing that was exported.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class RegistroExportacionesIT {

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
    @Autowired lateinit var exportaciones: ExportacionRepository
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var dataSource: DataSource

    private val semilla by lazy { SemillaRegistro(dataSource, empleadoRepository) }
    private val desde = LocalDate.parse("2025-03-01")
    private val hasta = LocalDate.parse("2025-03-31")

    /** A person with [dias] closed shifts in March 2025. */
    private fun personaConDias(dias: Int): EmpleadoEntity {
        val persona = semilla.empleado(nombre = "Nombre Distintivo", documento = "D${UUID.randomUUID().toString().take(8)}")
        (1..dias).forEach { d ->
            val dia = "2025-03-${d.toString().padStart(2, '0')}"
            semilla.fichaje(persona, "$dia 07:00", "$dia 15:00")
        }
        return persona
    }

    private fun registrosDe(persona: EmpleadoEntity) =
        exportaciones.buscar(persona.id, null, null, null, null)

    @Test
    fun `a complete export leaves one row with the row count and the fingerprint of the bytes sent`() {
        val persona = personaConDias(5)
        val out = ByteArrayOutputStream()

        val resultado = exportacion.exportar(
            AlcanceExportacion.Persona(persona.id), desde, hasta, persona.id, Role.EMPLEADO, out
        )

        val fila = registrosDe(persona).single()
        assertEquals(resultado.exportacionId, fila.id)
        assertTrue(fila.completada)
        assertEquals(5, fila.filas, "data rows only, not the header")
        assertEquals(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray())),
            fila.huella,
            "SHA-256 of exactly what the client received, BOM included"
        )
        assertEquals(AlcanceRegistro.PERSONA, fila.alcance)
        assertEquals(persona.id, fila.empleadoId)
        assertEquals(persona.id, fila.solicitanteId)
        assertEquals(Role.EMPLEADO, fila.rolSolicitante)
        assertEquals(desde, fila.desde)
        assertEquals(hasta, fila.hasta)
    }

    /**
     * A client that drops the connection mid-file. Each write is accepted whole
     * or refused whole, so the rows the client really has are the complete
     * records in what was accepted.
     */
    private class SalidaQueSeCorta(private val limite: Int) : OutputStream() {
        val aceptado = ByteArrayOutputStream()
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (aceptado.size() + len > limite) throw IOException("Broken pipe")
            aceptado.write(b, off, len)
        }
    }

    @Test
    fun `an interrupted export leaves one row, not completed, with the rows written and no fingerprint`() {
        val persona = personaConDias(20)
        val salida = SalidaQueSeCorta(limite = 900)

        assertFailsWith<IOException> {
            exportacion.exportar(
                AlcanceExportacion.Persona(persona.id), desde, hasta, persona.id, Role.EMPLEADO, salida
            )
        }

        val fila = registrosDe(persona).single()
        assertFalse(fila.completada)
        assertNull(fila.huella, "a half file identifies nothing anyone holds")
        val recibidas = LectorCsv.registros(salida.aceptado.toByteArray()).size - 1
        assertTrue(recibidas in 1 until 20, "the cut must fall mid-file for this test to mean anything: $recibidas")
        assertEquals(recibidas, fila.filas)
    }

    /** FR-026: identifiers, range, count and fingerprint. Nothing that was exported. */
    @Test
    fun `the row holds no names, documents or times`() {
        val persona = personaConDias(3)
        exportacion.exportar(
            AlcanceExportacion.Persona(persona.id), desde, hasta, persona.id, Role.EMPLEADO, ByteArrayOutputStream()
        )

        val valores = dataSource.connection.use { c ->
            c.prepareStatement("SELECT * FROM exportaciones WHERE empleado_id = ?").use { s ->
                s.setObject(1, persona.id)
                s.executeQuery().use { r ->
                    r.next()
                    (1..r.metaData.columnCount)
                        // When it was generated is the one time the row should hold.
                        .filter { r.metaData.getColumnName(it) != "generada_en" }
                        .map { r.getString(it).orEmpty() }
                }
            }
        }
        val todo = valores.joinToString("|")
        assertFalse(todo.contains("Nombre Distintivo"))
        assertFalse(todo.contains(persona.documentoIdentidad))
        assertFalse(todo.contains("07:00") || todo.contains("15:00") || todo.contains("8:00"), todo)
    }
}
