package com.granatum.core.scheduling

import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.infrastructure.database.repositories.InvoiceRecognitionRepository
import com.granatum.core.infrastructure.database.repositories.InvoiceRepository
import com.granatum.core.service.RecognitionQueue
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * Sends again the invoices left pending by a restart or a failure (SC-008).
 *
 * At most [MAX_ATTEMPTS] attempts per invoice. One the model always refuses, or
 * that always fails, would otherwise be sent - and paid for - every few minutes
 * for ever; after the third it waits for manual entry, which is always possible.
 */
@Component
class RecognitionRetryJob(
    private val invoices: InvoiceRepository,
    private val recognitions: InvoiceRecognitionRepository,
    private val queue: RecognitionQueue,
    @param:Value("\${invoices.recognition.retry-after-minutes:10}") private val afterMinutes: Long,
    private val clock: Clock = Clock.systemUTC()
) {
    companion object {
        const val MAX_ATTEMPTS = 3L
    }

    @Scheduled(cron = "\${invoices.recognition.retry-cron:0 */5 * * * *}")
    fun scheduled() = retry()

    /** Separate from the scheduled entry point so a test can drive it. Queues after the read commits. */
    fun retry() {
        candidates().forEach { queue.enqueue(it) }
    }

    fun candidates() = invoices
        .findIdsByStatusUploadedBefore(InvoiceStatus.PENDING_RECOGNITION, clock.instant().minus(Duration.ofMinutes(afterMinutes)))
        .filter { recognitions.countByInvoiceId(it) < MAX_ATTEMPTS }
}
