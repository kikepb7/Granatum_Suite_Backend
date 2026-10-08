package com.granatum.core

import com.granatum.core.domain.exception.AusenciaNoModificableException
import com.granatum.core.domain.exception.PersonaAusenciaInactivaException
import com.granatum.core.domain.exception.PersonaAusenciaNoEncontradaException
import com.granatum.core.domain.exception.RangoAusenciaInvalidoException
import com.granatum.core.domain.exception.ResolucionPropiaAusenciaException
import com.granatum.core.domain.exception.SaldoVacacionesInsuficienteException
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
import kotlin.test.assertNotNull

/** Holidays requested and resolved (feature 007, US1): FR-001, FR-003 to FR-005, FR-011, FR-013 to FR-016. */
@Testcontainers
@SpringBootTest(classes = [AbsencesTestApplication::class])
class SolicitudVacacionesIT : BaseAusenciasIT() {

    @Autowired lateinit var servicio: AusenciaService

    private val hoy: LocalDate get() = LocalDate.now(ZoneId.of("Europe/Madrid"))
    /** Far enough ahead to be in the future and inside a single year. */
    private val anio: Int get() = hoy.year + 1

    private fun dia(mes: Int, d: Int) = LocalDate.of(anio, mes, d)

    private fun vacaciones(desde: LocalDate, hasta: LocalDate) =
        NuevaAusencia(TipoAusencia.VACACIONES, causa = null, desde = desde, hasta = hasta, comentario = "Verano")

    @Test
    fun `a request is pending and shows in the balance as pending`() {
        val persona = directorio.activa()

        val ausencia = servicio.solicitar(persona, vacaciones(dia(8, 3), dia(8, 14)))

        assertEquals(EstadoAusencia.PENDIENTE, ausencia.estado)
        val saldo = servicio.saldo(persona, anio)
        assertEquals(30, saldo.derecho)
        assertEquals(12, saldo.pendientes)
        assertEquals(18, saldo.disponibles)
    }

    @Test
    fun `an ENCARGADO approves it, and who and when are recorded`() {
        val persona = directorio.activa()
        val encargado = UUID.randomUUID()
        val pedida = servicio.solicitar(persona, vacaciones(dia(8, 3), dia(8, 14)))

        val aprobada = servicio.aprobar(pedida.id, encargado)

        assertEquals(EstadoAusencia.APROBADA, aprobada.estado)
        assertEquals(encargado, aprobada.resueltaPor)
        assertNotNull(aprobada.resueltaEn)
        assertEquals(12, servicio.saldo(persona, anio).aprobados)
    }

    @Test
    fun `rejecting records the reason and gives the days back`() {
        val persona = directorio.activa()
        val pedida = servicio.solicitar(persona, vacaciones(dia(8, 3), dia(8, 14)))

        val rechazada = servicio.rechazar(pedida.id, UUID.randomUUID(), "Coincide con la feria")

        assertEquals(EstadoAusencia.RECHAZADA, rechazada.estado)
        assertEquals("Coincide con la feria", rechazada.motivoRechazo)
        assertEquals(30, servicio.saldo(persona, anio).disponibles)
    }

    @Test
    fun `nobody resolves their own request`() {
        val encargado = directorio.activa()
        val propia = servicio.solicitar(encargado, vacaciones(dia(8, 3), dia(8, 5)))

        assertFailsWith<ResolucionPropiaAusenciaException> { servicio.aprobar(propia.id, encargado) }
        assertFailsWith<ResolucionPropiaAusenciaException> { servicio.rechazar(propia.id, encargado, "no") }
    }

    @Test
    fun `a resolved request cannot be resolved again`() {
        val persona = directorio.activa()
        val pedida = servicio.solicitar(persona, vacaciones(dia(8, 3), dia(8, 5)))
        servicio.aprobar(pedida.id, UUID.randomUUID())

        assertFailsWith<AusenciaNoModificableException> { servicio.rechazar(pedida.id, UUID.randomUUID(), "tarde") }
        assertFailsWith<AusenciaNoModificableException> { servicio.aprobar(pedida.id, UUID.randomUUID()) }
    }

    @Test
    fun `more days than are left is refused`() {
        val persona = directorio.activa()
        servicio.solicitar(persona, vacaciones(dia(6, 1), dia(6, 25)))

        val e = assertFailsWith<SaldoVacacionesInsuficienteException> {
            servicio.solicitar(persona, vacaciones(dia(9, 1), dia(9, 6)))
        }
        assertEquals(true, e.message!!.contains("disponibles: 5"))
    }

    @Test
    fun `a person cannot ask for holidays that have already started`() {
        val persona = directorio.activa()

        assertFailsWith<RangoAusenciaInvalidoException> {
            servicio.solicitar(persona, vacaciones(hoy.minusDays(1), hoy.plusDays(2)))
        }
    }

    @Test
    fun `an end before the start, or more than 366 days, is refused`() {
        val persona = directorio.activa()

        assertFailsWith<RangoAusenciaInvalidoException> { servicio.solicitar(persona, vacaciones(dia(8, 14), dia(8, 3))) }
        assertFailsWith<RangoAusenciaInvalidoException> {
            servicio.solicitar(persona, vacaciones(dia(1, 1), dia(1, 1).plusDays(366)))
        }
    }

    @Test
    fun `nobody unknown or inactive gets a new absence`() {
        assertFailsWith<PersonaAusenciaNoEncontradaException> {
            servicio.solicitar(UUID.randomUUID(), vacaciones(dia(8, 3), dia(8, 5)))
        }
        assertFailsWith<PersonaAusenciaInactivaException> {
            servicio.solicitar(directorio.inactiva(), vacaciones(dia(8, 3), dia(8, 5)))
        }
    }
}
