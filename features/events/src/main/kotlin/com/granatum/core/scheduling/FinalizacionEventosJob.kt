package com.granatum.core.scheduling

import com.granatum.core.service.EventoService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Finishes the events of days already gone and puts their material back in the inventory
 * (feature 011, FR-007).
 *
 * Finishes every scheduled event *before today*, not just yesterday's: if the application was
 * down at midnight, the next run catches up. Idempotent by construction - it only moves
 * PROGRAMADO to FINALIZADO, under the event's row lock - so running it twice, or on several
 * instances at once, returns nothing twice.
 */
@Component
class FinalizacionEventosJob(private val eventos: EventoService) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Default 00:05 Europe/Madrid: just after the event's day has ended where it took place. */
    @Scheduled(cron = "\${events.finalizacion.cron:0 5 0 * * *}", zone = "Europe/Madrid")
    fun finalizarVencidos() {
        val finalizados = eventos.finalizarVencidos()
        // A count only: event names may carry people's names (principle VI).
        if (finalizados > 0) log.info("Finalizados {} eventos de días anteriores", finalizados)
    }
}
