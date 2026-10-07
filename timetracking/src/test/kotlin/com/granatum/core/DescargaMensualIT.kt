package com.granatum.core

import com.granatum.core.api.util.RangoFechas
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.ValoresIncoherentesException
import com.granatum.core.domain.model.ValoresFichaje
import com.granatum.core.domain.type.AlcanceRegistro
import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.domain.type.TipoPausa
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.infrastructure.database.repositories.ExportacionRepository
import com.granatum.core.service.CorreccionService
import com.granatum.core.service.ExportacionService
import com.granatum.core.service.FichajeService
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
import kotlin.test.assertTrue

/**
 * US2: the monthly download (FR-019 to FR-023, SC-003).
 *
 * Its total must be **exactly** the on-screen summary's, always (FR-020). The
 * month seeded here holds every case where two separate calculations would
 * drift: a corrected day, a reconstructed one, an unresolved one, a shift that
 * started the evening before the month, and one that starts at exactly 00:00
 * on the first of the next month - a round time a correction can easily set,
 * and the boundary an inclusive "between" gets wrong.
 */
@SpringBootTest(classes = [TimetrackingTestApplication::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DescargaMensualIT {

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
    @Autowired lateinit var fichajeService: FichajeService
    @Autowired lateinit var correcciones: CorreccionService
    @Autowired lateinit var exportaciones: ExportacionRepository
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var dataSource: DataSource

    private val semilla by lazy { SemillaRegistro(dataSource, empleadoRepository) }

    private lateinit var persona: EmpleadoEntity
    private lateinit var encargado: EmpleadoEntity

    @BeforeAll
    fun sembrar() {
        persona = semilla.empleado(nombre = "Paula Parcial", tipo = TipoContrato.PARCIAL)
        encargado = semilla.empleado(nombre = "Encargado")

        // Belongs to February: it started there, even though it ends in March.
        semilla.fichaje(persona, "2025-02-28 22:00", "2025-03-01 02:00")

        semilla.fichaje(
            persona, "2025-03-03 09:00", "2025-03-03 14:00",
            listOf(SemillaRegistro.Pausa(TipoPausa.DESCANSO, "2025-03-03 11:00", "2025-03-03 11:15"))
        )

        val corregido = semilla.fichaje(persona, "2025-03-04 09:00", "2025-03-04 14:00")
        val c = correcciones.solicitar(
            corregido, persona.id, Role.EMPLEADO, "Sali mas tarde",
            ValoresFichaje(semilla.instante("2025-03-04 09:00"), semilla.instante("2025-03-04 15:30"), emptyList())
        )
        correcciones.aprobar(c.id, encargado.id, Role.ENCARGADO)

        val reconstruido = semilla.fichaje(persona, "2025-03-05 09:00", null, estado = EstadoFichaje.INCOMPLETO, fueIncompleto = true)
        val r = correcciones.solicitar(
            reconstruido, persona.id, Role.EMPLEADO, "Olvide fichar la salida",
            ValoresFichaje(semilla.instante("2025-03-05 09:00"), semilla.instante("2025-03-05 13:00"), emptyList())
        )
        correcciones.aprobar(r.id, encargado.id, Role.ENCARGADO)

        // Still unresolved: contributes nothing, in both.
        semilla.fichaje(persona, "2025-03-06 09:00", null, estado = EstadoFichaje.INCOMPLETO, fueIncompleto = true)

        // Exactly midnight on April 1st: April's, not March's.
        semilla.fichaje(persona, "2025-04-01 00:00", "2025-04-01 04:00")
    }

    private fun descargar(
        anio: Int = 2025,
        mes: Int = 3,
        solicitante: UUID = persona.id,
        rol: Role = Role.EMPLEADO,
        empleadoId: UUID = persona.id
    ): ByteArray {
        val out = ByteArrayOutputStream()
        exportacion.descargarMensual(empleadoId, anio, mes, solicitante, rol, out)
        return out.toByteArray()
    }

    /** The cell under [columna] in the block row that starts with [etiqueta]. */
    private fun bloque(bytes: ByteArray, etiqueta: String, columna: String? = null): String {
        val registros = LectorCsv.registros(bytes)
        val fila = registros.single { it.first() == etiqueta }
        return if (columna == null) fila[1] else fila[registros.first().indexOf(columna)]
    }

    @Test
    fun `every shift of the calendar month, and only those`() {
        val filas = LectorCsv.filas(descargar())
        assertEquals(listOf("2025-03-03", "2025-03-04", "2025-03-05", "2025-03-06"), filas.map { it["Fecha"] })
    }

    /** FR-020, SC-003: the same number as the on-screen summary, from the same function. */
    @Test
    fun `the monthly total is exactly the on-screen summary's`() {
        val resumen = fichajeService.resumenMensual(persona.id, persona.id, Role.EMPLEADO, 2025, 3)
        val bytes = descargar()

        assertEquals(resumen.totalMinutosTrabajados.toString(), bloque(bytes, "Total del mes", "Minutos trabajados"))
        assertEquals(285 + 390 + 240, resumen.totalMinutosTrabajados, "and that number is the right one")
        assertEquals("15:15", bloque(bytes, "Total del mes", "Horas trabajadas"))
        assertEquals(
            LectorCsv.filas(bytes).sumOf { it["Minutos trabajados"]!!.ifEmpty { "0" }.toInt() },
            resumen.totalMinutosTrabajados,
            "the total is the sum of the rows shown above it"
        )
    }

    @Test
    fun `the corrected and the reconstructed day are flagged`() {
        val filas = LectorCsv.filas(descargar())
        assertEquals("Sí", filas.single { it["Fecha"] == "2025-03-04" }["Corregido"])
        val reconstruido = filas.single { it["Fecha"] == "2025-03-05" }
        assertEquals("Sí", reconstruido["Completado a posteriori"])
        assertEquals("Sí", reconstruido["Corregido"])
    }

    /** FR-021: art. 12.4.c obliges this summary for part-time contracts. */
    @Test
    fun `the contract type is stated`() {
        assertEquals("PARCIAL", bloque(descargar(), "Tipo de contrato"))
    }

    /** FR-022 */
    @Test
    fun `a past month is closed and the current one is not`() {
        assertEquals("Sí", bloque(descargar(), "Mes cerrado"))

        val hoy = LocalDate.now(RangoFechas.ZONA)
        assertEquals("No", bloque(descargar(anio = hoy.year, mes = hoy.monthValue), "Mes cerrado"))
    }

    /** FR-023 */
    @Test
    fun `the person, ENCARGADO, ADMIN and REPRESENTANTE may download it, another EMPLEADO may not`() {
        listOf(Role.ENCARGADO, Role.ADMIN, Role.REPRESENTANTE).forEach { rol ->
            assertEquals(4, LectorCsv.filas(descargar(solicitante = UUID.randomUUID(), rol = rol)).size, "$rol")
        }
        assertFailsWith<ForbiddenException> { descargar(solicitante = encargado.id, rol = Role.EMPLEADO) }
    }

    @Test
    fun `a month outside 1 to 12 is rejected`() {
        assertFailsWith<ValoresIncoherentesException> { descargar(mes = 13) }
        assertFailsWith<ValoresIncoherentesException> { descargar(mes = 0) }
    }

    @Test
    fun `it is recorded as MENSUAL covering the whole month`() {
        val otra = semilla.empleado()
        val out = ByteArrayOutputStream()
        exportacion.descargarMensual(otra.id, 2025, 2, otra.id, Role.EMPLEADO, out)

        val registro = exportaciones.buscar(otra.id, null, null, null, null).single()
        assertEquals(AlcanceRegistro.MENSUAL, registro.alcance)
        assertEquals(LocalDate.parse("2025-02-01"), registro.desde)
        assertEquals(LocalDate.parse("2025-02-28"), registro.hasta)
        assertTrue(registro.completada)
        assertEquals(0, registro.filas)
    }
}
