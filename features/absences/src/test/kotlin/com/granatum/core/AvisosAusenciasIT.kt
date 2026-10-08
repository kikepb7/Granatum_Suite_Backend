package com.granatum.core

import com.granatum.core.domain.event.AvisoDominio
import com.granatum.core.domain.event.TipoAviso
import com.granatum.core.domain.model.TipoAusencia
import com.granatum.core.service.AusenciaService
import com.granatum.core.service.NuevaAusencia
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

/** What this module tells the notices module (feature 008): FR-003, FR-005. */
@Testcontainers
@SpringBootTest(classes = [AbsencesTestApplication::class])
@RecordApplicationEvents
class AvisosAusenciasIT : BaseAusenciasIT() {

    @Autowired lateinit var servicio: AusenciaService
    @Autowired lateinit var eventos: ApplicationEvents

    private val anio get() = LocalDate.now(ZoneId.of("Europe/Madrid")).year + 1
    private fun vacaciones(d: Int) = NuevaAusencia(TipoAusencia.VACACIONES, null, LocalDate.of(anio, 3, d), LocalDate.of(anio, 3, d + 1), null)
    private fun aviso(tipo: TipoAviso, ref: UUID) = eventos.stream(AvisoDominio::class.java).filter { it.tipo == tipo && it.referenciaId == ref }.toList().single()

    @Test
    fun `a request is announced as pending with its author, and its resolution to the person`() {
        val persona = directorio.activa()
        val encargado = UUID.randomUUID()

        val pedida = servicio.solicitar(persona, vacaciones(2))
        assertEquals(persona, aviso(TipoAviso.AUSENCIA_PENDIENTE, pedida.id).autorId)

        servicio.aprobar(pedida.id, encargado)
        assertEquals(persona, aviso(TipoAviso.AUSENCIA_APROBADA, pedida.id).titularId)

        val otra = servicio.solicitar(persona, vacaciones(10))
        servicio.rechazar(otra.id, encargado, "No")
        assertEquals(persona, aviso(TipoAviso.AUSENCIA_RECHAZADA, otra.id).titularId)
    }

    @Test
    fun `an absence registered on someone's behalf is announced to them as approved`() {
        val persona = directorio.activa()

        val registrada = servicio.registrar(UUID.randomUUID(), persona, NuevaAusencia(TipoAusencia.BAJA_MEDICA, null, LocalDate.now(), null, null))

        assertEquals(persona, aviso(TipoAviso.AUSENCIA_APROBADA, registrada.id).titularId)
    }
}
