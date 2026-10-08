package com.granatum.core

import com.granatum.core.domain.event.TipoAviso
import com.granatum.core.infrastructure.database.repositories.NotificacionRepository
import com.granatum.core.scheduling.LimpiezaNotificacionesJob
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

/** Old notices go (feature 008, US5): FR-011. */
@Testcontainers
@SpringBootTest(classes = [NotificationsTestApplication::class])
class LimpiezaNotificacionesIT : BaseNotificacionesIT() {

    @Autowired lateinit var job: LimpiezaNotificacionesJob
    @Autowired lateinit var repositorio: NotificacionRepository
    @Autowired lateinit var transacciones: TransactionTemplate
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun crear(persona: UUID, diasAtras: Long, leida: Boolean): UUID {
        val id = UUID.randomUUID()
        val cuando = Instant.now().minus(diasAtras, ChronoUnit.DAYS)
        transacciones.executeWithoutResult {
            repositorio.insertarSiNoExiste(id, persona, TipoAviso.AUSENCIA_APROBADA.name, UUID.randomUUID(), cuando)
        }
        if (leida) jdbc.update("UPDATE notificaciones SET leida_en = ? WHERE id = ?", Timestamp.from(cuando), id)
        return id
    }

    @Test
    fun `read ones after 90 days and any after 180 are deleted, the rest stay`() {
        val persona = UUID.randomUUID()
        val leidaVieja = crear(persona, 91, leida = true)
        val leidaReciente = crear(persona, 89, leida = true)
        val noLeidaDe100 = crear(persona, 100, leida = false)
        val noLeidaDe181 = crear(persona, 181, leida = false)

        job.limpiar()

        val quedan = repositorio.findAllByDestinatarioIdOrderByCreadaEnDescIdDesc(persona, Pageable.ofSize(10)).map { it.id }.toSet()
        assertEquals(setOf(leidaReciente, noLeidaDe100), quedan)
        assertEquals(false, leidaVieja in quedan || noLeidaDe181 in quedan)
    }
}
