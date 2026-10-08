package com.granatum.core

import com.granatum.core.domain.exception.AusenciaNoEncontradaException
import com.granatum.core.domain.exception.AusenciaNoModificableException
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

/** Cancelling (feature 007, US4): FR-012. */
@Testcontainers
@SpringBootTest(classes = [AbsencesTestApplication::class])
class CancelacionAusenciasIT : BaseAusenciasIT() {

    @Autowired lateinit var servicio: AusenciaService

    private val hoy get() = LocalDate.now(ZoneId.of("Europe/Madrid"))

    private fun vacaciones(desde: LocalDate, hasta: LocalDate) = NuevaAusencia(TipoAusencia.VACACIONES, null, desde, hasta, null)

    @Test
    fun `a pending request of one's own is cancelled and its days come back`() {
        val persona = directorio.activa()
        val pedida = servicio.solicitar(persona, vacaciones(hoy.plusDays(30), hoy.plusDays(34)))

        val cancelada = servicio.cancelar(pedida.id, persona)

        assertEquals(EstadoAusencia.CANCELADA, cancelada.estado)
        assertNotNull(cancelada.canceladaEn)
        assertEquals(0, servicio.saldo(persona, hoy.plusDays(30).year).pendientes)
    }

    @Test
    fun `approved holidays that start tomorrow can still be cancelled`() {
        val persona = directorio.activa()
        val pedida = servicio.solicitar(persona, vacaciones(hoy.plusDays(1), hoy.plusDays(2)))
        servicio.aprobar(pedida.id, UUID.randomUUID())

        assertEquals(EstadoAusencia.CANCELADA, servicio.cancelar(pedida.id, persona).estado)
    }

    @Test
    fun `approved holidays that have started cannot`() {
        val persona = directorio.activa()
        val enCurso = servicio.registrar(UUID.randomUUID(), persona, vacaciones(hoy.minusDays(1), hoy.plusDays(3)))

        assertFailsWith<AusenciaNoModificableException> { servicio.cancelar(enCurso.id, persona) }
    }

    @Test
    fun `a rejected request is not cancelled`() {
        val persona = directorio.activa()
        val pedida = servicio.solicitar(persona, vacaciones(hoy.plusDays(40), hoy.plusDays(41)))
        servicio.rechazar(pedida.id, UUID.randomUUID(), "No")

        assertFailsWith<AusenciaNoModificableException> { servicio.cancelar(pedida.id, persona) }
    }

    /** Not 403: an EMPLEADO must not learn that someone else's id exists. */
    @Test
    fun `someone else's absence is not found`() {
        val persona = directorio.activa()
        val pedida = servicio.solicitar(persona, vacaciones(hoy.plusDays(50), hoy.plusDays(51)))

        assertFailsWith<AusenciaNoEncontradaException> { servicio.cancelar(pedida.id, directorio.activa()) }
    }
}
