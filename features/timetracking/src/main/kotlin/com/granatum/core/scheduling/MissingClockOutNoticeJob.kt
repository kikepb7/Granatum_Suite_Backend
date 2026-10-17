package com.granatum.core.scheduling

import com.granatum.core.domain.event.DomainNotice
import com.granatum.core.domain.event.NoticeType
import com.granatum.core.domain.type.ShiftStatus
import com.granatum.core.infrastructure.database.repositories.ShiftRepository
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
 * `timetracking.missing-clock-out.hours` (10) becomes a notice to its owner. It
 * only **reads** the register - principle III is untouched. The same shift is
 * seen again on every run; the notices module stores the notice once
 * (FR-007).
 *
 * Logs nothing: which shifts are open, and whose, is a person's working
 * pattern (principle VI).
 */
@Component
class MissingClockOutNoticeJob(
    private val shifts: ShiftRepository,
    private val notices: ApplicationEventPublisher,
    @param:Value("\${timetracking.missing-clock-out.hours:10}") private val hours: Long,
    private val clock: Clock = Clock.systemUTC()
) {

    @Scheduled(cron = "\${timetracking.missing-clock-out.cron:0 */30 * * * *}", zone = "Europe/Madrid")
    @Transactional(readOnly = true)
    fun sendNotices(): Int {
        val openShifts = shifts.findAllByStatusAndClockInBefore(ShiftStatus.IN_PROGRESS, clock.instant().minus(hours, ChronoUnit.HOURS))
        openShifts.forEach { notices.publishEvent(DomainNotice(NoticeType.SHIFT_WITHOUT_CLOCK_OUT, it.id, ownerId = it.employee.id)) }
        return openShifts.size
    }
}
