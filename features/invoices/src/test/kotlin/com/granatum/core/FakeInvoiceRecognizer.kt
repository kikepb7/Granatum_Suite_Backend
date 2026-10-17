package com.granatum.core

import com.granatum.core.domain.model.ProposedVatLine
import com.granatum.core.domain.model.RecognizedProposal
import com.granatum.core.domain.port.RecognitionDocument
import com.granatum.core.domain.port.InvoiceRecognizer
import com.granatum.core.domain.port.RecognitionResult
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The double of [InvoiceRecognizer] that every test of the suite uses: no
 * test calls the real API, spends money or needs the network (research.md
 * D-019). Scripted per test, and it records whether it was called with a
 * transaction open - which must never happen (D-005).
 */
class FakeInvoiceRecognizer : InvoiceRecognizer {

    @Volatile override var active: Boolean = true

    /** What the next calls return. */
    @Volatile var script: (RecognitionDocument) -> RecognitionResult = { recognized(proposal()) }

    /** When set, every call waits on it before answering. */
    @Volatile var waitFor: CountDownLatch? = null

    data class Call(val inTransaction: Boolean, val mediaType: String)

    val calls = CopyOnWriteArrayList<Call>()

    override fun recognize(document: RecognitionDocument): RecognitionResult {
        calls += Call(TransactionSynchronizationManager.isActualTransactionActive(), document.mediaType)
        waitFor?.await(30, TimeUnit.SECONDS)
        return script(document)
    }

    fun reset() {
        active = true
        script = { recognized(proposal()) }
        waitFor = null
        calls.clear()
    }

    companion object {
        const val MODEL = "fake-model"

        fun recognized(p: RecognizedProposal) = RecognitionResult.Recognized(p, MODEL, 3000, 800)

        /**
         * A correct invoice from a supplier to the company, with two VAT rates:
         * 100.00 at 21% and 50.00 at 10% → 150.00 + 21.00 + 5.00 = 176.00.
         */
        fun proposal(
            isInvoice: Boolean = true,
            multipleInvoices: Boolean = false,
            issuerName: String? = "Flores del Sur S.L.",
            issuerNif: String? = "A58818501",
            recipientName: String? = "Floristeria Granatum S.L.",
            recipientNif: String? = "B12345674",
            number: String? = "2026-0815",
            issueDate: String? = "2026-10-02",
            concept: String? = "Rosas rojas",
            currency: String? = "EUR",
            vatLines: List<ProposedVatLine> = listOf(
                ProposedVatLine("21.00", "100.00", "21.00", "0.00", null),
                ProposedVatLine("10.00", "50.00", "5.00", "0.00", null)
            ),
            withholdings: String? = "0.00",
            total: String? = "176.00",
            corrective: Boolean? = false,
            uncertainFields: List<String> = emptyList()
        ) = RecognizedProposal(
            isInvoice, multipleInvoices, issuerName, issuerNif, recipientName, recipientNif,
            number, issueDate, concept, currency, vatLines, withholdings, total, corrective, uncertainFields
        )
    }
}

/** Registers the double in place of the real recogniser. Import it in a test with `@Import`. */
@TestConfiguration
class WithFakeInvoiceRecognizer {
    @Bean
    @Primary
    fun fakeInvoiceRecognizer(): FakeInvoiceRecognizer = FakeInvoiceRecognizer()
}
