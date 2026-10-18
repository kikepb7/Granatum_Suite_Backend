package com.granatum.core.domain.event

import com.granatum.core.domain.type.EntityId

/**
 * An invoice changed in a way the Google spreadsheet must reflect (feature
 * 012, US3, research.md D-002): it was confirmed, or a confirmed one was
 * corrected or discarded.
 *
 * `invoices` publishes it inside the transaction of the change, with Spring's
 * `ApplicationEventPublisher`; `sheets` listens `BEFORE_COMMIT` and queues the
 * write in that same transaction (research.md D-003). Neither knows the other.
 *
 * Carries the id only, like [DomainNotice]: the writer reads the invoice's
 * current state when it runs, so however many changes there were, the last one
 * is written, and the event carries no personal data.
 */
data class InvoiceSheetChange(val invoiceId: EntityId)
