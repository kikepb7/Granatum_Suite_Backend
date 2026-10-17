package com.granatum.core

import com.granatum.core.domain.model.ProposedVatLine
import com.granatum.core.domain.model.RecognizedProposal
import com.granatum.core.domain.port.InvoiceRecognizer
import com.granatum.core.domain.port.RecognitionDocument
import com.granatum.core.domain.port.RecognitionResult
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * For the app tests that need an invoice recognised: a recogniser that always
 * proposes the same invoice, from a self-employed supplier with distinctive
 * values that a log search can look for. Never the real API.
 */
@TestConfiguration
class WithFixedInvoiceRecognizer {

    companion object {
        const val NAME = "Proveedorasingular Lopez"
        const val NIF = "73918264J"
        const val CONCEPT = "Conceptoinconfundible de prueba"
        const val TOTAL = "4321.87"

        /** 3571.79 + 21% = 750.08 → 4321.87. */
        val PROPOSAL = RecognizedProposal(
            isInvoice = true, multipleInvoices = false,
            issuerName = NAME, issuerNif = NIF,
            recipientName = "Floristeria Granatum S.L.", recipientNif = "B12345674",
            number = "SING-${java.util.UUID.randomUUID().toString().take(8)}", issueDate = "2026-10-02", concept = CONCEPT, currency = "EUR",
            vatLines = listOf(ProposedVatLine("21.00", "3571.79", "750.08", "0.00", null)),
            withholdings = "0.00", total = TOTAL, corrective = false, uncertainFields = emptyList()
        )
    }

    @Bean
    @Primary
    fun fixedInvoiceRecognizer(): InvoiceRecognizer = object : InvoiceRecognizer {
        override val active = true
        override fun recognize(document: RecognitionDocument) =
            RecognitionResult.Recognized(PROPOSAL, "modelo-fijo", 1, 1)
    }
}
