package com.granatum.core.scheduling

import com.granatum.core.api.util.RangoFechas
import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.infrastructure.database.repositories.FichajeRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * Marks shifts left open from a previous day as `INCOMPLETO` (FR-012).
 *
 * Runs daily rather than waiting for the person's next clock-in. The lazy
 * alternative looks cheaper but leaves the register lying for as long as
 * someone is away - a fichaje still EN_CURSO three weeks into their holiday
 * claims they are at work - and that is a legal problem, not a usability one.
 *
 * Idempotent by construction: it only moves EN_CURSO to INCOMPLETO, so running
 * it twice, or on several application instances at once, changes nothing the
 * first run did not already do. That is what makes it safe without a lock.
 *
 * `fueIncompleto` is set and never cleared, so a day completed later through an
 * approved correction stays distinguishable from one closed at the time
 * (SC-010).
 */
@Component
class MarcadoFichajesIncompletosJob(
    private val fichajeRepository: FichajeRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Default 04:00 Europe/Madrid: after any plausible night shift has ended,
     * and before the working day starts, so nobody finds their shift marked
     * mid-jornada.
     */
    @Scheduled(
        cron = "\${timetracking.incompletos.cron:0 0 4 * * *}",
        zone = "Europe/Madrid"
    )
    @Transactional
    fun marcarIncompletos() {
        // Cut-off is the start of today in Madrid, so "a previous day" means the
        // previous *civil* day. Using a UTC day boundary would mis-classify
        // shifts started between midnight and 02:00 Spanish summer time, which
        // are still the day before in UTC.
        val corte = RangoFechas.inicioDelDia(LocalDate.now(clock.withZone(RangoFechas.ZONA)))

        val abiertos = fichajeRepository.findAllByEstadoAndEntradaBefore(
            EstadoFichaje.EN_CURSO,
            corte
        )

        abiertos.forEach { fichaje ->
            fichaje.estado = EstadoFichaje.INCOMPLETO
            fichaje.fueIncompleto = true
            fichajeRepository.save(fichaje)
        }

        // Counts only: an identifier here would put a person's working pattern
        // into the logs (principle VI).
        if (abiertos.isNotEmpty()) {
            log.info("Marcados {} fichajes como INCOMPLETO (corte {})", abiertos.size, corte)
        }
    }
}
