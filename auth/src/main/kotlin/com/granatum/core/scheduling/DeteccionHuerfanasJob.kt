package com.granatum.core.scheduling

import com.granatum.core.service.DeteccionHuerfanasService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Nightly orphan-account sweep (FR-029c), so orphans surface without anyone
 * having to ask. The on-demand listing is `GET /api/auth/cuentas/huerfanas`.
 *
 * Logs **the count only**. Account and employee ids are identifiers associated
 * with a person, and principle VI keeps them out of the logs; whoever needs the
 * ids asks the endpoint, which is restricted to `ADMIN`.
 */
@Component
class DeteccionHuerfanasJob(
    private val deteccion: DeteccionHuerfanasService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${auth.huerfanas.cron:0 30 4 * * *}", zone = "Europe/Madrid")
    fun detectar() {
        val total = deteccion.buscar().size
        if (total > 0) {
            // WARN, not INFO: an orphan account means a person was removed
            // somewhere without their credentials following, which somebody
            // should look at.
            log.warn("Hay {} cuentas de acceso huerfanas; ver GET /api/auth/cuentas/huerfanas", total)
        }
    }
}
