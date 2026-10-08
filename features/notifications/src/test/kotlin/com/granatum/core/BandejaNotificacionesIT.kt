package com.granatum.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.granatum.core.api.dto.NotificacionResponse
import com.granatum.core.domain.event.TipoAviso
import com.granatum.core.domain.exception.NotificacionNoEncontradaException
import com.granatum.core.domain.model.Notificacion
import com.granatum.core.infrastructure.database.repositories.NotificacionRepository
import com.granatum.core.service.NotificacionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A person's inbox (feature 008, US4): FR-006, FR-009, FR-010, SC-003, SC-004. */
@Testcontainers
@SpringBootTest(classes = [NotificationsTestApplication::class])
class BandejaNotificacionesIT : BaseNotificacionesIT() {

    @Autowired lateinit var servicio: NotificacionService
    @Autowired lateinit var repositorio: NotificacionRepository
    @Autowired lateinit var transacciones: TransactionTemplate
    // A local mapper: Boot 4 serves JSON with Jackson 3 and registers no
    // Jackson 2 ObjectMapper bean. The wire format itself is checked end to
    // end in app (NotificacionesDePuntaAPuntaIT); this checks the DTO's shape.
    private val json = ObjectMapper().findAndRegisterModules()

    private fun crear(destinatario: UUID, tipo: TipoAviso = TipoAviso.AUSENCIA_PENDIENTE, hace: Long = 0): UUID {
        val id = UUID.randomUUID()
        transacciones.executeWithoutResult {
            repositorio.insertarSiNoExiste(id, destinatario, tipo.name, UUID.randomUUID(), Instant.now().minus(hace, ChronoUnit.MINUTES))
        }
        return id
    }

    @Test
    fun `the inbox is newest first, and can show only the unread`() {
        val persona = UUID.randomUUID()
        val vieja = crear(persona, hace = 30)
        val nueva = crear(persona, hace = 1)
        servicio.marcarLeida(vieja, persona)

        assertEquals(listOf(nueva, vieja), servicio.bandeja(persona, soloNoLeidas = false).map { it.id })
        assertEquals(listOf(nueva), servicio.bandeja(persona, soloNoLeidas = true).map { it.id })
    }

    @Test
    fun `it holds at most 100`() {
        val persona = UUID.randomUUID()
        repeat(105) { crear(persona, hace = it.toLong()) }

        assertEquals(100, servicio.bandeja(persona, false).size)
        assertEquals(105, servicio.noLeidas(persona))
    }

    @Test
    fun `marking one read lowers the count, marking all leaves none`() {
        val persona = UUID.randomUUID()
        val una = crear(persona)
        repeat(2) { crear(persona) }

        servicio.marcarLeida(una, persona)
        assertEquals(2, servicio.noLeidas(persona))
        servicio.marcarLeida(una, persona) // again: nothing changes, no error

        servicio.marcarTodasLeidas(persona)
        assertEquals(0, servicio.noLeidas(persona))
    }

    /** FR-010, SC-004: someone else's notice does not exist for you. */
    @Test
    fun `someone else's notice cannot be seen or marked`() {
        val duena = UUID.randomUUID()
        val ajena = crear(duena)
        val otra = UUID.randomUUID()

        assertTrue(servicio.bandeja(otra, false).isEmpty())
        assertFailsWith<NotificacionNoEncontradaException> { servicio.marcarLeida(ajena, otra) }
        servicio.marcarTodasLeidas(otra)
        assertEquals(1, servicio.noLeidas(duena), "marking all of yours touches nobody else's")
    }

    /** FR-006, SC-003: a fixed text per type, and only ids besides. */
    @Test
    fun `every notice reads as a fixed message with ids and nothing else`() {
        val persona = UUID.randomUUID()
        TipoAviso.entries.forEach { crear(persona, it) }

        val enviadas = servicio.bandeja(persona, false)
        assertEquals(TipoAviso.entries.toSet(), enviadas.map { it.tipo }.toSet())
        enviadas.forEach {
            assertEquals(Notificacion.MENSAJES.getValue(it.tipo), it.mensaje)
            val campos = json.readTree(json.writeValueAsString(NotificacionResponse.de(it))).fieldNames().asSequence().toSet()
            assertEquals(setOf("id", "tipo", "referenciaId", "mensaje", "creadaEn", "leidaEn"), campos)
        }
    }
}
