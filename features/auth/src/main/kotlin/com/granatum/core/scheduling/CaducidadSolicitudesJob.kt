package com.granatum.core.scheduling

import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.database.repositories.SolicitudRegistroRepository
import com.granatum.core.service.RegistradorEventosSeguridad
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Expires sign-up requests nobody resolved (feature 005, FR-025): a pending
 * request holds a name, a DNI, an email and a password hash, and keeping them
 * with no one to act on them breaks GDPR art. 5.1.e.
 *
 * One `UPDATE` that also empties the personal fields - V20's CHECKs refuse an
 * expired row that keeps them. One security event per request, so the log
 * says how many expired without saying whose.
 */
@Component
class CaducidadSolicitudesJob(
    private val solicitudes: SolicitudRegistroRepository,
    private val eventos: RegistradorEventosSeguridad,
    @param:Value("\${auth.registro.caducidad-dias}") private val dias: Long,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${auth.registro.caducidad-cron}", zone = "Europe/Madrid")
    fun caducar(): Int {
        val ahora = clock.instant()
        val caducadas = solicitudes.caducarAnterioresA(ahora.minus(dias, ChronoUnit.DAYS), ahora)
        repeat(caducadas) { eventos.registrar(TipoEventoSeguridad.REGISTRO_CADUCADO) }
        if (caducadas > 0) log.info("Caducadas {} solicitudes de registro sin resolver", caducadas)
        return caducadas
    }
}
