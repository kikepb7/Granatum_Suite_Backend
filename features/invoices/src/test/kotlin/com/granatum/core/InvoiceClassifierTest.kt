package com.granatum.core

import com.granatum.core.domain.model.InvoiceType
import com.granatum.core.domain.service.InvoiceClassifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Research D-014: issued or received, by the company's own tax id. */
class InvoiceClassifierTest {

    private val company = "B12345674"

    @Test
    fun `issued when the company is the issuer, received when it is the recipient`() {
        assertEquals(InvoiceType.ISSUED, InvoiceClassifier.classify(company, "B12345674", "A58818501"))
        assertEquals(InvoiceType.RECEIVED, InvoiceClassifier.classify(company, "A58818501", "B12345674"))
    }

    @Test
    fun `tax ids compare normalised - VAT prefix, spaces, hyphens, case`() {
        assertEquals(InvoiceType.ISSUED, InvoiceClassifier.classify(company, "ES b-1234 5674", "A58818501"))
        assertEquals(InvoiceType.RECEIVED, InvoiceClassifier.classify(company, "A58818501", "esB12345674"))
    }

    @Test
    fun `neither, or nothing to compare with, is left undecided`() {
        assertNull(InvoiceClassifier.classify(company, "A58818501", "12345678Z"))
        assertNull(InvoiceClassifier.classify(company, null, null))
        assertNull(InvoiceClassifier.classify(null, "B12345674", "A58818501"))
    }
}
