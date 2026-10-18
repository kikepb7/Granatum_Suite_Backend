package com.granatum.core

import com.granatum.core.domain.contract.InvoiceSheetRow
import com.granatum.core.domain.contract.InvoiceSheetSource
import com.granatum.core.domain.type.EntityId
import org.springframework.stereotype.Component
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap

/**
 * Stands in for `invoices`' side of the `InvoiceSheetSource` contract (a plain
 * bean, like [FakeShiftSheetSource]). [confirmed] holds the invoices that are
 * confirmed now; anything else - a draft, a discarded one - is simply absent.
 */
@Component
class FakeInvoiceSheetSource : InvoiceSheetSource {
    val confirmed = ConcurrentHashMap<EntityId, InvoiceSheetRow>()

    override fun row(invoiceId: EntityId): InvoiceSheetRow? = confirmed[invoiceId]

    override fun month(month: YearMonth): List<InvoiceSheetRow> =
        confirmed.values.filter { YearMonth.from(it.issueDate) == month }
            .sortedWith(compareBy({ it.issueDate }, { it.number.orEmpty() }, { it.id.toString() }))

    fun reset() = confirmed.clear()
}
