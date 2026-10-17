package com.granatum.core.scheduling

import com.granatum.core.infrastructure.database.repositories.NotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Deletes old notices (feature 008, FR-011, research D-005): read ones after
 * `read-days` (90), any after `all-days` (180). A notice is not a record
 * with legal value - the register it points at is, and that is untouched.
 */
@Component
class NotificationCleanupJob(
    private val notifications: NotificationRepository,
    @param:Value("\${notifications.cleanup.read-days}") private val readDays: Long,
    @param:Value("\${notifications.cleanup.all-days}") private val allDays: Long,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${notifications.cleanup.cron}", zone = "Europe/Madrid")
    @Transactional
    fun cleanUp(): Int {
        val now = clock.instant()
        val deleted = notifications.cleanUp(now.minus(readDays, ChronoUnit.DAYS), now.minus(allDays, ChronoUnit.DAYS))
        if (deleted > 0) log.info("Eliminadas {} notificaciones antiguas", deleted)
        return deleted
    }
}
