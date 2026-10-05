package com.granatum.core.scheduling

import com.granatum.core.api.util.RangoFechas
import com.granatum.core.infrastructure.database.entities.DepuracionRetencionEntity
import com.granatum.core.infrastructure.database.repositories.DepuracionRetencionRepository
import com.granatum.core.infrastructure.database.repositories.RetencionPurgaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.Period

/**
 * Deletes records whose four-year retention period has fully elapsed
 * (constitution principle III since v2.0.0, FR-031b to FR-031d).
 *
 * ## Why deleting is required rather than merely allowed
 *
 * Keeping everything for ever is not "complying harder". The RD-ley fixes four
 * years, and past that the GDPR storage limitation principle (art. 5.1.e) makes
 * continued retention its own breach. The duty to destroy is as real as the duty
 * to keep; what admits no exception is the period.
 *
 * ## Why this is the only deleting code
 *
 * No endpoint, no role and no manual operation can remove a record, before or
 * after the period. This job and [RetencionPurgaRepository] are the whole of it,
 * and that repository's queries are bounded by the cut-off, so even this code
 * cannot touch a record whose period is still running.
 *
 * ## Why it ships disabled
 *
 * The basis for destroying these records is that they were available
 * beforehand, so the purge must not run in an environment without the monthly
 * download (FR-031d). Until the export feature exists, nothing is deleted -
 * which is the safe side to be wrong on: keeping too much rather than too
 * little.
 */
@Component
class DepuracionRetencionJob(
    private val purga: RetencionPurgaRepository,
    private val depuraciones: DepuracionRetencionRepository,

    /**
     * Off by default, and must stay off until the monthly download exists. A
     * default of `true` would make an omission in configuration destroy data.
     */
    @param:Value("\${timetracking.retencion.habilitada:false}")
    private val habilitada: Boolean,

    @param:Value("\${timetracking.retencion.anios:4}")
    private val anios: Int,

    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        cron = "\${timetracking.retencion.cron:0 30 4 * * *}",
        zone = "Europe/Madrid"
    )
    fun depurar() {
        if (!habilitada) {
            log.debug("Depuracion de retencion deshabilitada; no se borra nada")
            return
        }
        ejecutar()
    }

    /**
     * Separated from the scheduled entry point so a test can drive it without
     * waiting for a cron, and so the enabled check is not what a test ends up
     * exercising.
     */
    @Transactional
    fun ejecutar(): DepuracionRetencionEntity {
        val hoy = LocalDate.now(clock.withZone(RangoFechas.ZONA))

        // Strictly "fully elapsed": a record whose period ends tomorrow is
        // untouchable. The cut-off is the start of the day exactly `anios` ago,
        // and the queries use `<`, so a shift from precisely four years ago
        // today survives one more day.
        val fechaCorte = hoy.minus(Period.ofYears(anios))
        val corte = RangoFechas.inicioDelDia(fechaCorte)

        // Children first: the foreign keys are deliberately not ON DELETE
        // CASCADE, so a cascade cannot quietly take rows nobody counted.
        val pausas = purga.borrarPausasAnterioresA(corte)
        val solicitudes = purga.borrarSolicitudesAnterioresA(corte)
        val eventos = purga.borrarEventosAnterioresA(corte)
        val fichajes = purga.borrarFichajesAnterioresA(corte)

        val registro = depuraciones.save(
            DepuracionRetencionEntity(
                ejecutadaEn = clock.instant(),
                fechaCorte = fechaCorte,
                fichajesEliminados = fichajes,
                pausasEliminadas = pausas,
                eventosEliminados = eventos,
                solicitudesEliminadas = solicitudes
            )
        )

        // Counts and a date, never an identifier: the audit row survives the
        // very period the purge exists to honour, so putting personal data in it
        // would defeat its own purpose.
        if (fichajes > 0) {
            log.info(
                "Depuracion de retencion: {} fichajes, {} pausas, {} eventos, " +
                    "{} solicitudes (corte {})",
                fichajes, pausas, eventos, solicitudes, fechaCorte
            )
        }

        return registro
    }
}
