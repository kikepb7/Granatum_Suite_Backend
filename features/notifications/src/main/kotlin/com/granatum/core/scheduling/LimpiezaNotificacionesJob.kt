package com.granatum.core.scheduling

import com.granatum.core.infrastructure.database.repositories.NotificacionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Deletes old notices (feature 008, FR-011, research D-005): read ones after
 * `dias-leidas` (90), any after `dias-todas` (180). A notice is not a record
 * with legal value - the register it points at is, and that is untouched.
 */
@Component
class LimpiezaNotificacionesJob(
    private val notificaciones: NotificacionRepository,
    @param:Value("\${notifications.limpieza.dias-leidas}") private val diasLeidas: Long,
    @param:Value("\${notifications.limpieza.dias-todas}") private val diasTodas: Long,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${notifications.limpieza.cron}", zone = "Europe/Madrid")
    @Transactional
    fun limpiar(): Int {
        val ahora = clock.instant()
        val borradas = notificaciones.limpiar(ahora.minus(diasLeidas, ChronoUnit.DAYS), ahora.minus(diasTodas, ChronoUnit.DAYS))
        if (borradas > 0) log.info("Eliminadas {} notificaciones antiguas", borradas)
        return borradas
    }
}
