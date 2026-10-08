package com.granatum.core.scheduling

import com.granatum.core.infrastructure.database.repositories.PurgaEventosSeguridadRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Retention for `eventos_seguridad` (declared debt of feature 002, closed here).
 *
 * An audit table that grows for ever is a breach of GDPR art. 5.1.e from the
 * other side: it is not the working-time register, so principle III's four
 * years do not apply, but it holds which account signed in, failed or was
 * locked and when. Two years by default - long enough to investigate an
 * incident after the fact - and `AUTH_EVENTOS_RETENCION_DIAS` changes it; the
 * value is the product owner's decision, and this is the documented default.
 */
@Component
class PurgaEventosSeguridadJob(
    private val purga: PurgaEventosSeguridadRepository,
    @param:Value("\${auth.eventos.retencion-dias:730}") private val dias: Long,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${auth.eventos.retencion-cron:0 55 4 * * *}", zone = "Europe/Madrid")
    fun depurar(): Int {
        val borrados = purga.depurarAnterioresA(clock.instant().minus(dias, ChronoUnit.DAYS))
        if (borrados > 0) log.info("Depurados {} eventos de seguridad de más de {} días", borrados, dias)
        return borrados
    }
}
