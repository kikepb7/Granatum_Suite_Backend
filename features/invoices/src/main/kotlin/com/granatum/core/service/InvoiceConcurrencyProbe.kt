package com.granatum.core.service

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * A point inside a confirmation, a correction or a discard, right after its
 * checks (the quarter lock taken) and before it writes.
 * In production it does nothing. It exists so that concurrency tests can make
 * two operations meet exactly there and show what the locks and the unique
 * indexes prevent, instead of hoping the scheduler produces the race
 * (LogicalDuplicateInvoiceIT, ConcurrentQuarterCloseIT; finding T1 of the analysis).
 */
interface InvoiceConcurrencyProbe {
    fun afterChecks(invoiceId: UUID)
}

@Component
class NoOpInvoiceConcurrencyProbe : InvoiceConcurrencyProbe {
    override fun afterChecks(invoiceId: UUID) = Unit
}
