package com.granatum.core.scheduling

import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.infrastructure.database.repositories.FacturaReconocimientoRepository
import com.granatum.core.infrastructure.database.repositories.FacturaRepository
import com.granatum.core.service.ColaReconocimiento
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * Sends again the invoices left pending by a restart or a failure (SC-008).
 *
 * At most [MAX_INTENTOS] attempts per invoice. One the model always refuses, or
 * that always fails, would otherwise be sent - and paid for - every few minutes
 * for ever; after the third it waits for manual entry, which is always possible.
 */
@Component
class ReintentoReconocimientoJob(
    private val facturas: FacturaRepository,
    private val reconocimientos: FacturaReconocimientoRepository,
    private val cola: ColaReconocimiento,
    @param:Value("\${invoices.reconocimiento.reintento-tras-minutos:10}") private val trasMinutos: Long,
    private val clock: Clock = Clock.systemUTC()
) {
    companion object {
        const val MAX_INTENTOS = 3L
    }

    @Scheduled(cron = "\${invoices.reconocimiento.reintento-cron:0 */5 * * * *}")
    fun programado() = reintentar()

    /** Separate from the scheduled entry point so a test can drive it. Queues after the read commits. */
    fun reintentar() {
        candidatas().forEach { cola.encolar(it) }
    }

    fun candidatas() = facturas
        .findIdsPorEstadoSubidasAntesDe(EstadoFactura.PENDIENTE_RECONOCER, clock.instant().minus(Duration.ofMinutes(trasMinutos)))
        .filter { reconocimientos.countByFacturaId(it) < MAX_INTENTOS }
}
