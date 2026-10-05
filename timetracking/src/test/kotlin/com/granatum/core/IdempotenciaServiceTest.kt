package com.granatum.core

import com.granatum.core.domain.exception.ClientEventIdReutilizadoException
import com.granatum.core.domain.type.TipoOperacionFichaje
import com.granatum.core.infrastructure.database.entities.FichajeEventoEntity
import com.granatum.core.service.IdempotenciaService
import com.granatum.core.service.RegistradorEventos
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The three outcomes of an idempotency lookup, isolated from the database.
 */
class IdempotenciaServiceTest {

    private val registrador = mockk<RegistradorEventos>()
    private val service = IdempotenciaService(registrador)

    private val clave = UUID.randomUUID()
    private val cuerpo = "$clave|ENTRADA|2026-10-05T07:00:00Z"

    private fun eventoCon(huella: String) = FichajeEventoEntity(
        clientEventId = clave,
        empleadoId = UUID.randomUUID(),
        fichajeId = UUID.randomUUID(),
        tipoOperacion = TipoOperacionFichaje.ENTRADA,
        occurredAt = Instant.parse("2026-10-05T07:00:00Z"),
        receivedAt = Instant.parse("2026-10-05T07:30:00Z"),
        huellaPeticion = huella,
        estadoRespuesta = 201,
        cuerpoRespuesta = """{"id":"abc"}"""
    )

    @Test
    fun `an unseen key lets the operation through`() {
        every { registrador.buscarPorClientEventId(clave) } returns null

        assertNull(
            service.respuestaPrevia(clave, cuerpo),
            "null means 'new': the caller should go ahead"
        )
    }

    @Test
    fun `a replayed key with the same body returns the stored response`() {
        val huella = "huella-estable"
        every { registrador.buscarPorClientEventId(clave) } returns eventoCon(huella)
        every { registrador.huella(cuerpo) } returns huella

        val previa = service.respuestaPrevia(clave, cuerpo)

        assertEquals(201, previa?.estado)
        assertEquals("""{"id":"abc"}""", previa?.cuerpo)
    }

    /**
     * The case the fingerprint exists for. Comparing only the key would hand
     * this caller **another operation's** response, and nothing would reveal it.
     */
    @Test
    fun `the same key with a different body is a conflict`() {
        every { registrador.buscarPorClientEventId(clave) } returns eventoCon("huella-original")
        every { registrador.huella(cuerpo) } returns "huella-distinta"

        assertFailsWith<ClientEventIdReutilizadoException> {
            service.respuestaPrevia(clave, cuerpo)
        }
    }
}
