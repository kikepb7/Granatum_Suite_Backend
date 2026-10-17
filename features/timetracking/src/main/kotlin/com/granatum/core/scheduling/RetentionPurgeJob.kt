package com.granatum.core.scheduling

import com.granatum.core.api.util.CivilDateRange
import com.granatum.core.service.RetentionPeriod
import com.granatum.core.infrastructure.database.entities.RetentionPurgeEntity
import com.granatum.core.infrastructure.database.repositories.RetentionPurgeRepository
import com.granatum.core.infrastructure.database.repositories.RetentionDeletionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

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
 * after the period. This job and [RetentionDeletionRepository] are the whole of it,
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
class RetentionPurgeJob(
    private val deletion: RetentionDeletionRepository,
    private val purges: RetentionPurgeRepository,

    /**
     * Off by default, and must stay off until the monthly download exists. A
     * default of `true` would make an omission in configuration destroy data.
     */
    @param:Value("\${timetracking.retention.enabled:false}")
    private val enabled: Boolean,

    /**
     * The boundary comes from here and nowhere else, so the purge and the export
     * (feature 003) can never disagree about which records still exist.
     */
    private val retentionPeriod: RetentionPeriod,

    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        cron = "\${timetracking.retention.cron:0 30 4 * * *}",
        zone = "Europe/Madrid"
    )
    fun purge() {
        if (!enabled) {
            log.debug("Depuracion de retencion deshabilitada; no se borra nada")
            return
        }
        run()
    }

    /**
     * Separated from the scheduled entry point so a test can drive it without
     * waiting for a cron, and so the enabled check is not what a test ends up
     * exercising.
     */
    @Transactional
    fun run(): RetentionPurgeEntity {
        // Strictly "fully elapsed": a record whose period ends tomorrow is
        // untouchable. The cut-off is the start of the day exactly the retention
        // period ago, and the queries use `<`, so a shift from precisely four
        // years ago today survives one more day.
        val cutoffDate = retentionPeriod.cutoffDate()
        val cutoff = CivilDateRange.startOfDay(cutoffDate)

        // Children first: the foreign keys are deliberately not ON DELETE
        // CASCADE, so a cascade cannot quietly take rows nobody counted.
        val breaks = deletion.deleteBreaksBefore(cutoff)
        val correctionRequests = deletion.deleteRequestsBefore(cutoff)
        val events = deletion.deleteEventsBefore(cutoff)
        val shifts = deletion.deleteShiftsBefore(cutoff)
        // By date, not instant: an export covers whole days. `to` before the
        // cut-off date means every day it covered is being purged right now.
        val exports = deletion.deleteExportsBefore(cutoffDate)

        val purgeRecord = purges.save(
            RetentionPurgeEntity(
                executedAt = clock.instant(),
                cutoffDate = cutoffDate,
                deletedShifts = shifts,
                deletedBreaks = breaks,
                deletedEvents = events,
                deletedRequests = correctionRequests,
                deletedExports = exports
            )
        )

        // Counts and a date, never an identifier: the audit row survives the
        // very period the purge exists to honour, so putting personal data in it
        // would defeat its own purpose.
        if (shifts > 0 || exports > 0) {
            log.info(
                "Depuracion de retencion: {} fichajes, {} pausas, {} eventos, " +
                    "{} solicitudes, {} exportaciones (corte {})",
                shifts, breaks, events, correctionRequests, exports, cutoffDate
            )
        }

        return purgeRecord
    }
}
