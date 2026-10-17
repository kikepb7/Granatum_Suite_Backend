package com.granatum.core.scheduling

import com.granatum.core.infrastructure.database.repositories.SecurityEventPurgeRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Retention for `security_events` (declared debt of feature 002, closed here).
 *
 * An audit table that grows for ever is a breach of GDPR art. 5.1.e from the
 * other side: it is not the working-time register, so principle III's four
 * years do not apply, but it holds which account signed in, failed or was
 * locked and when. Two years by default - long enough to investigate an
 * incident after the fact - and `AUTH_SECURITY_EVENTS_RETENTION_DAYS` changes it; the
 * value is the product owner's decision, and this is the documented default.
 */
@Component
class SecurityEventPurgeJob(
    private val purgeRepository: SecurityEventPurgeRepository,
    @param:Value("\${auth.security-events.retention-days:730}") private val days: Long,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${auth.security-events.retention-cron:0 55 4 * * *}", zone = "Europe/Madrid")
    fun purge(): Int {
        val deleted = purgeRepository.deleteOlderThan(clock.instant().minus(days, ChronoUnit.DAYS))
        if (deleted > 0) log.info("Depurados {} eventos de seguridad de más de {} días", deleted, days)
        return deleted
    }
}
