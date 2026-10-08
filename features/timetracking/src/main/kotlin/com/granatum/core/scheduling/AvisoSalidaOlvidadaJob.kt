package com.granatum.core.scheduling

import com.granatum.core.domain.event.AvisoDominio
import com.granatum.core.domain.event.TipoAviso
import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.infrastructure.database.repositories.FichajeRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * "Have you forgotten to clock out?" (feature 008, FR-001).
 *
 * Every half hour, each shift open for longer than
 * `timetracking.aviso-sin-salida.horas` (10) becomes a notice to its owner. It
 * only **reads** the register - principle III is untouched. The same shift is
 * seen again on every run; the notices module stores the notice once
 * (FR-007).
 *
 * Logs nothing: which shifts are open, and whose, is a person's working
 * pattern (principle VI).
 */
@Component
class AvisoSalidaOlvidadaJob(
    private val fichajes: FichajeRepository,
    private val avisos: ApplicationEventPublisher,
    @param:Value("\${timetracking.aviso-sin-salida.horas:10}") private val horas: Long,
    private val clock: Clock = Clock.systemUTC()
) {

    @Scheduled(cron = "\${timetracking.aviso-sin-salida.cron:0 */30 * * * *}", zone = "Europe/Madrid")
    @Transactional(readOnly = true)
    fun avisar(): Int {
        val abiertos = fichajes.findAllByEstadoAndEntradaBefore(EstadoFichaje.EN_CURSO, clock.instant().minus(horas, ChronoUnit.HOURS))
        abiertos.forEach { avisos.publishEvent(AvisoDominio(TipoAviso.FICHAJE_SIN_SALIDA, it.id, titularId = it.empleado.id)) }
        return abiertos.size
    }
}
