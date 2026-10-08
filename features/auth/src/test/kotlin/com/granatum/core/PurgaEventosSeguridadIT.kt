package com.granatum.core

import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.database.entities.EventoSeguridadEntity
import com.granatum.core.infrastructure.database.repositories.EventoSeguridadRepository
import com.granatum.core.infrastructure.database.repositories.PurgaEventosSeguridadRepository
import com.granatum.core.scheduling.PurgaEventosSeguridadJob
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

/** Retention of security events (declared debt of feature 002, closed). */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class PurgaEventosSeguridadIT : BaseAuthIT() {

    @Autowired lateinit var eventos: EventoSeguridadRepository
    @Autowired lateinit var purga: PurgaEventosSeguridadRepository

    private val ahora: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    @Test
    fun `events past the retention period go, the rest stay`() {
        val cuenta = UUID.randomUUID()
        eventos.save(EventoSeguridadEntity(cuentaId = cuenta, tipo = TipoEventoSeguridad.LOGIN_CORRECTO, ocurridoEn = ahora.minus(731, ChronoUnit.DAYS)))
        eventos.save(EventoSeguridadEntity(cuentaId = cuenta, tipo = TipoEventoSeguridad.LOGIN_FALLIDO, ocurridoEn = ahora.minus(729, ChronoUnit.DAYS)))

        PurgaEventosSeguridadJob(purga, dias = 730, clock = Clock.fixed(ahora, ZoneOffset.UTC)).depurar()

        assertEquals(listOf(TipoEventoSeguridad.LOGIN_FALLIDO), eventos.findAllByCuentaIdOrderByOcurridoEnDesc(cuenta).map { it.tipo })
    }

    /** The table stays append-only for everything that writes events. */
    @Test
    fun `the events repository still offers no way to delete`() {
        assertEquals(
            emptyList(),
            EventoSeguridadRepository::class.java.methods.map { it.name }.filter { it.startsWith("delete") || it.startsWith("remove") }
        )
    }
}
