package com.granatum.core

import com.granatum.core.domain.exception.AusenciaNoEncontradaException
import com.granatum.core.domain.model.EstadoAusencia
import com.granatum.core.domain.model.TipoAusencia
import com.granatum.core.service.AusenciaService
import com.granatum.core.service.NuevaAusencia
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The calendar and the balance (feature 007, US5): FR-014, FR-015, FR-018. */
@Testcontainers
@SpringBootTest(classes = [AbsencesTestApplication::class])
class ConsultaAusenciasIT : BaseAusenciasIT() {

    @Autowired lateinit var servicio: AusenciaService

    private val anio get() = LocalDate.now(ZoneId.of("Europe/Madrid")).year + 2
    private fun dia(mes: Int, d: Int) = LocalDate.of(anio, mes, d)
    private fun vacaciones(desde: LocalDate, hasta: LocalDate) = NuevaAusencia(TipoAusencia.VACACIONES, null, desde, hasta, null)

    @Test
    fun `the staff calendar brings every person's absences that touch the range, by state if asked`() {
        val una = directorio.activa()
        val otra = directorio.activa()
        val agosto = servicio.solicitar(una, vacaciones(dia(7, 28), dia(8, 3)))
        val aprobada = servicio.solicitar(otra, vacaciones(dia(8, 20), dia(8, 22)))
        servicio.aprobar(aprobada.id, UUID.randomUUID())
        servicio.solicitar(otra, vacaciones(dia(9, 10), dia(9, 11)))

        val enAgosto = servicio.buscar(null, null, dia(8, 1), dia(8, 31)).map { it.id }
        assertTrue(agosto.id in enAgosto && aprobada.id in enAgosto, "both touch August")
        assertEquals(2, enAgosto.count { it == agosto.id || it == aprobada.id })

        val soloAprobadas = servicio.buscar(null, EstadoAusencia.APROBADA, dia(8, 1), dia(8, 31)).map { it.id }
        assertTrue(aprobada.id in soloAprobadas && agosto.id !in soloAprobadas)

        val deUna = servicio.buscar(una, null, dia(1, 1), dia(12, 31))
        assertTrue(deUna.all { it.empleadoId == una } && deUna.isNotEmpty())
    }

    @Test
    fun `whoever may only see their own gets not-found for someone else's`() {
        val una = directorio.activa()
        val ajena = servicio.solicitar(directorio.activa(), vacaciones(dia(10, 1), dia(10, 2)))

        assertFailsWith<AusenciaNoEncontradaException> { servicio.obtener(ajena.id, una, veTodas = false) }
        assertEquals(ajena.id, servicio.obtener(ajena.id, una, veTodas = true).id)
    }

    @Test
    fun `the entitlement set by the ADMIN replaces the default`() {
        val persona = directorio.activa()

        servicio.fijarDerecho(persona, anio, 17, UUID.randomUUID())
        assertEquals(17, servicio.saldo(persona, anio).derecho)

        servicio.fijarDerecho(persona, anio, 20, UUID.randomUUID())
        assertEquals(20, servicio.saldo(persona, anio).derecho)
        assertEquals(30, servicio.saldo(persona, anio + 1).derecho, "other years keep the default")
    }

    @Test
    fun `holidays across new year use each year's own balance`() {
        val persona = directorio.activa()
        servicio.fijarDerecho(persona, anio + 1, 3, UUID.randomUUID())

        servicio.solicitar(persona, vacaciones(LocalDate.of(anio, 12, 28), LocalDate.of(anio + 1, 1, 2)))

        assertEquals(4, servicio.saldo(persona, anio).pendientes)
        assertEquals(2, servicio.saldo(persona, anio + 1).pendientes)
        assertEquals(1, servicio.saldo(persona, anio + 1).disponibles)
    }
}
