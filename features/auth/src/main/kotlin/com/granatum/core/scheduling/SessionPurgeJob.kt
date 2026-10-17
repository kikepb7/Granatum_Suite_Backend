package com.granatum.core.scheduling

import com.granatum.core.infrastructure.database.repositories.RefreshSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Deletes refresh-session rows that have been dead for longer than
 * `auth.session-purge.days` (30 by default).
 *
 * **An addition of the plan, not a requirement of the spec** (D-015), marked so
 * it can be withdrawn.
 *
 * ## Why it exists
 *
 * Without it the table grows without bound. Renewing every 15 minutes, one
 * person generates roughly 35,000 rows a year, which makes it the fastest-growing
 * table in the system, and a consumed token proves nothing that
 * `security_events` does not already record.
 *
 * ## What it can and cannot touch
 *
 * Only rows already dead - used, revoked or expired - and only those dead for
 * longer than the cut-off. A **live** session is untouchable however old it is.
 * The bound is in the query itself, not in this class.
 *
 * Principle III protects the working-time register, not this table, but its
 * shape is respected anyway because it is the product's pattern: only an
 * automatic process deletes, and there is no endpoint and no role that can.
 */
@Component
class SessionPurgeJob(
    private val sessions: RefreshSessionRepository,
    @param:Value("\${auth.session-purge.days}") private val days: Long,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${auth.session-purge.cron:0 45 4 * * *}", zone = "Europe/Madrid")
    fun purge(): Int {
        val cutoff = clock.instant().minus(days, ChronoUnit.DAYS)
        val deleted = sessions.purgeDeadBefore(cutoff)
        // A count and a date; no identifiers (principle VI).
        if (deleted > 0) log.info("Purgadas {} sesiones muertas anteriores a {}", deleted, cutoff)
        return deleted
    }
}
