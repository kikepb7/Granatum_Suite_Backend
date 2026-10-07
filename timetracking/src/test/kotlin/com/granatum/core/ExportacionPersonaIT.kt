package com.granatum.core

import com.granatum.core.api.util.RangoFechas
import com.granatum.core.domain.exception.EmpleadoNotFoundException
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.ValoresIncoherentesException
import com.granatum.core.domain.model.AlcanceExportacion
import com.granatum.core.domain.model.ValoresFichaje
import com.granatum.core.domain.model.ValoresPausa
import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoPausa
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.infrastructure.database.repositories.FichajeRepository
import com.granatum.core.service.CorreccionService
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
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * US1: a person downloads their own register, and every row says what the
 * register says (FR-001 to FR-006, FR-013, FR-016, FR-018; SC-001, SC-002,
 * SC-010).
 *
 * One person is seeded once with every kind of day the file has to get right,
 * and each test reads the same export from its own angle.
 */
@SpringBootTest(classes = [TimetrackingTestApplication::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportacionPersonaIT {

    companion object {
        // Started here and not by @Testcontainers: with PER_CLASS, Spring builds
        // the context while creating the test instance, before that extension's
        // beforeAll would start the container.
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
    @Autowired lateinit var correcciones: CorreccionService
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var fichajeRepository: FichajeRepository
    @Autowired lateinit var dataSource: DataSource

    private val semilla by lazy { SemillaRegistro(dataSource, empleadoRepository) }

    private lateinit var ana: EmpleadoEntity
    private lateinit var luis: EmpleadoEntity
    private lateinit var marta: EmpleadoEntity

    private val desde = LocalDate.parse("2025-03-01")
    private val hasta = LocalDate.parse("2025-10-31")

    private fun valores(entrada: String, salida: String, vararg pausas: Triple<TipoPausa, String, String>) =
        ValoresFichaje(
            semilla.instante(entrada),
            semilla.instante(salida),
            pausas.map { (t, i, f) -> ValoresPausa(t, semilla.instante(i), semilla.instante(f)) }
        )

    @BeforeAll
    fun sembrar() {
        ana = semilla.empleado(nombre = "Ana Pérez", documento = "12345678Z")
        luis = semilla.empleado(nombre = "Luis Gil")
        marta = semilla.empleado(nombre = "Marta Ruiz")
        val otra = semilla.empleado(nombre = "Otra Persona")

        // Outside the range on both sides, and someone else's day inside it.
        semilla.fichaje(ana, "2025-02-28 07:00", "2025-02-28 15:00")
        semilla.fichaje(ana, "2025-11-01 07:00", "2025-11-01 15:00")
        semilla.fichaje(otra, "2025-03-03 07:00", "2025-03-03 15:00")

        // A plain day with a break, with a PENDING correction that must not show.
        val normal = semilla.fichaje(
            ana, "2025-03-03 07:00", "2025-03-03 16:00",
            listOf(SemillaRegistro.Pausa(TipoPausa.COMIDA, "2025-03-03 11:00", "2025-03-03 11:30"))
        )
        correcciones.solicitar(normal, ana.id, Role.EMPLEADO, "Pendiente, no aplicada", valores("2025-03-03 06:00", "2025-03-03 16:00"))

        // A split day: two shifts, two rows. The first carries a REJECTED correction.
        val manana = semilla.fichaje(ana, "2025-03-04 07:00", "2025-03-04 11:00")
        semilla.fichaje(ana, "2025-03-04 15:00", "2025-03-04 19:00")
        val rechazada = correcciones.solicitar(manana, ana.id, Role.EMPLEADO, "Sera rechazada", valores("2025-03-04 06:00", "2025-03-04 11:00"))
        correcciones.rechazar(rechazada.id, luis.id, Role.ENCARGADO, "No procede")

        // Corrected twice: the originals are those of the FIRST approval.
        val corregido = semilla.fichaje(ana, "2025-03-05 07:00", "2025-03-05 16:00")
        val primera = correcciones.solicitar(
            corregido, ana.id, Role.EMPLEADO, "Olvide la pausa",
            valores("2025-03-05 07:00", "2025-03-05 16:00", Triple(TipoPausa.COMIDA, "2025-03-05 11:00", "2025-03-05 11:45"))
        )
        correcciones.aprobar(primera.id, luis.id, Role.ENCARGADO)
        val segunda = correcciones.solicitar(
            corregido, ana.id, Role.EMPLEADO, "Sali mas tarde",
            valores("2025-03-05 07:00", "2025-03-05 16:30", Triple(TipoPausa.COMIDA, "2025-03-05 11:00", "2025-03-05 11:45"))
        )
        correcciones.aprobar(segunda.id, marta.id, Role.ENCARGADO)

        // INCOMPLETO and never fixed; and INCOMPLETO later reconstructed.
        semilla.fichaje(ana, "2025-03-06 08:00", null, estado = EstadoFichaje.INCOMPLETO, fueIncompleto = true)
        val reconstruido = semilla.fichaje(ana, "2025-03-07 08:00", null, estado = EstadoFichaje.INCOMPLETO, fueIncompleto = true)
        val reconstruccion = correcciones.solicitar(reconstruido, ana.id, Role.EMPLEADO, "Olvide fichar la salida", valores("2025-03-07 08:00", "2025-03-07 16:00"))
        correcciones.aprobar(reconstruccion.id, luis.id, Role.ENCARGADO)

        // Across midnight; and across the change to winter time (03:00 -> 02:00).
        semilla.fichaje(ana, "2025-05-09 22:00", "2025-05-10 06:00")
        semilla.fichaje(ana, "2025-10-25 22:00", "2025-10-26 07:00")

        // Still open.
        semilla.fichaje(ana, "2025-10-30 07:00", null)
    }

    private fun exportar(
        empleadoId: UUID = ana.id,
        solicitante: UUID = ana.id,
        rol: Role = Role.EMPLEADO,
        d: LocalDate = desde,
        h: LocalDate = hasta
    ): ByteArray {
        val out = ByteArrayOutputStream()
        exportacion.exportar(AlcanceExportacion.Persona(empleadoId), d, h, solicitante, rol, out)
        return out.toByteArray()
    }

    private fun filas() = LectorCsv.filas(exportar())

    private val fechaHora = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(RangoFechas.ZONA)
    private val hora = DateTimeFormatter.ofPattern("HH:mm").withZone(RangoFechas.ZONA)

    @Test
    fun `one row per shift in the range, in order, and only this person's`() {
        val filas = filas()
        assertEquals(9, filas.size, "nine shifts of Ana inside the range")
        assertTrue(filas.all { it["Persona"] == "Ana Pérez" })
        assertEquals(
            listOf("2025-03-03", "2025-03-04", "2025-03-04", "2025-03-05", "2025-03-06", "2025-03-07", "2025-05-09", "2025-10-25", "2025-10-30"),
            filas.map { it["Fecha"] }
        )
    }

    /** SC-002: every row crossed with the shift as it stands in the database. */
    @Test
    fun `every row matches the shift in force`() {
        val vigentes = fichajeRepository
            .findAllByEmpleadoIdAndEntradaBetweenOrderByEntradaDesc(ana.id, RangoFechas.inicioDelDia(desde), RangoFechas.inicioDelDia(hasta.plusDays(1)))
            .sortedBy { it.entrada }
        val filas = filas()
        assertEquals(vigentes.size, filas.size)

        vigentes.zip(filas).forEach { (f, fila) ->
            assertEquals(fechaHora.format(f.entrada), fila["Entrada"])
            assertEquals(f.salida?.let(fechaHora::format).orEmpty(), fila["Salida"])
            assertEquals(f.estado.name, fila["Estado"])
            assertEquals(
                if (f.estado == EstadoFichaje.CERRADO) f.minutosTrabajados.toString() else "",
                fila["Minutos trabajados"]
            )
            assertEquals(
                f.pausas.sortedBy { it.inicio }.joinToString(", ") { "${it.tipo} ${hora.format(it.inicio)}-${it.fin?.let(hora::format).orEmpty()}" },
                fila["Pausas"]
            )
            assertEquals(if (f.fueIncompleto) "Sí" else "No", fila["Completado a posteriori"])
            assertEquals("12345678Z", fila["Documento"])
        }
    }

    @Test
    fun `a break shows its kind, start and end`() {
        val fila = filas().first()
        assertEquals("COMIDA 11:00-11:30", fila["Pausas"])
        assertEquals("8:30", fila["Horas trabajadas"])
        assertEquals("510", fila["Minutos trabajados"])
        assertEquals("No", fila["Corregido"], "a pending correction is not applied")
    }

    @Test
    fun `a split day gives two rows and a rejected correction does not mark it`() {
        val partido = filas().filter { it["Fecha"] == "2025-03-04" }
        assertEquals(listOf("2025-03-04 07:00", "2025-03-04 15:00"), partido.map { it["Entrada"] })
        assertTrue(partido.all { it["Corregido"] == "No" && it["Correcciones"] == "" })
    }

    /** FR-004, FR-005: values in force, originals of the first approval, every approval named. */
    @Test
    fun `a corrected shift shows the values in force, the first originals and who corrected it`() {
        val fila = filas().single { it["Fecha"] == "2025-03-05" }
        assertEquals("2025-03-05 16:30", fila["Salida"], "the latest approved values")
        assertEquals("COMIDA 11:00-11:45", fila["Pausas"])
        assertEquals("Sí", fila["Corregido"])
        assertEquals("2025-03-05 07:00", fila["Entrada original"])
        assertEquals("2025-03-05 16:00", fila["Salida original"], "what was clocked, not what the first correction left")
        assertEquals("", fila["Pausas originales"])

        val partes = fila["Correcciones"]!!.split(" | ")
        assertEquals(2, partes.size, "approved corrections only, oldest first")
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2} solicitada por Ana Pérez, aprobada por Luis Gil""").matches(partes[0]), partes[0])
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2} solicitada por Ana Pérez, aprobada por Marta Ruiz""").matches(partes[1]), partes[1])
    }

    /** FR-006 and SC-010. */
    @Test
    fun `open and incomplete shifts leave hours empty, and a reconstructed one says so`() {
        val filas = filas()
        listOf("2025-03-06", "2025-10-30").forEach { fecha ->
            val fila = filas.single { it["Fecha"] == fecha }
            assertEquals("", fila["Salida"])
            assertEquals("", fila["Horas trabajadas"], "never a zero that claims nothing was worked")
            assertEquals("", fila["Minutos trabajados"])
        }
        assertEquals("INCOMPLETO", filas.single { it["Fecha"] == "2025-03-06" }["Estado"])
        assertEquals("EN_CURSO", filas.single { it["Fecha"] == "2025-10-30" }["Estado"])

        val reconstruido = filas.single { it["Fecha"] == "2025-03-07" }
        assertEquals("CERRADO", reconstruido["Estado"])
        assertEquals("Sí", reconstruido["Completado a posteriori"])
        assertEquals("Sí", reconstruido["Corregido"])
        assertEquals("2025-03-07 08:00", reconstruido["Entrada original"])
        assertEquals(
            "",
            reconstruido["Salida original"],
            "the original had no exit; the stored snapshot fills it with the entry, which must not be shown as a time"
        )
    }

    @Test
    fun `a shift across midnight belongs to the day it started and shows its exit with the date`() {
        val fila = filas().single { it["Entrada"] == "2025-05-09 22:00" }
        assertEquals("2025-05-09", fila["Fecha"])
        assertEquals("2025-05-10 06:00", fila["Salida"])
        assertEquals("480", fila["Minutos trabajados"])
    }

    /**
     * 22:00 to 07:00 across the change to winter time is ten real hours: the
     * clock goes back at 03:00. Shown in local time at both ends, computed on
     * instants (FR-003).
     */
    @Test
    fun `a shift across the change to winter time shows local times and counts real minutes`() {
        val fila = filas().single { it["Fecha"] == "2025-10-25" }
        assertEquals("2025-10-25 22:00", fila["Entrada"])
        assertEquals("2025-10-26 07:00", fila["Salida"])
        assertEquals("600", fila["Minutos trabajados"])
        assertEquals("10:00", fila["Horas trabajadas"])
    }

    @Test
    fun `a range with no shifts gives only the header`() {
        val bytes = exportar(d = LocalDate.parse("2024-01-01"), h = LocalDate.parse("2024-01-31"))
        assertEquals(1, LectorCsv.registros(bytes).size)
        assertEquals("Persona", LectorCsv.cabecera(bytes).first())
    }

    @Test
    fun `an inverted range is rejected`() {
        assertFailsWith<ValoresIncoherentesException> { exportar(d = hasta, h = desde) }
    }

    /** FR-013: an EMPLEADO is bound to the token subject, whatever id they send. */
    @Test
    fun `an EMPLEADO cannot export someone else`() {
        assertFailsWith<ForbiddenException> { exportar(empleadoId = luis.id, solicitante = ana.id) }
    }

    @Test
    fun `a manager can export a person`() {
        assertEquals(9, LectorCsv.filas(exportar(solicitante = luis.id, rol = Role.ENCARGADO)).size)
    }

    @Test
    fun `an unknown person is not found`() {
        assertFailsWith<EmpleadoNotFoundException> {
            exportar(empleadoId = UUID.randomUUID(), solicitante = luis.id, rol = Role.ADMIN)
        }
    }

    /** FR-018: reading the register writes nothing to it. */
    @Test
    fun `exporting changes nothing in the register`() {
        val antes = semilla.huellaDelRegistro()
        exportar()
        exportar(solicitante = luis.id, rol = Role.ADMIN)
        assertEquals(antes, semilla.huellaDelRegistro())
    }
}
