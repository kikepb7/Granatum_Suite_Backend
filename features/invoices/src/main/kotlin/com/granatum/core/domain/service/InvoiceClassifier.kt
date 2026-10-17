package com.granatum.core.domain.service

import com.granatum.core.domain.model.InvoiceType
import com.granatum.core.validation.NifValidator

/**
 * Issued or received, by the company's own tax id (research.md D-014). Tax ids
 * compare normalised: case, spaces, hyphens and the `ES` VAT prefix do not
 * matter. Null when neither party is the company: the reviewer decides.
 */
object InvoiceClassifier {
    fun classify(companyNif: String?, issuerNif: String?, recipientNif: String?): InvoiceType? {
        val own = companyNif?.let(NifValidator::normalizeTaxId) ?: return null
        return when (own) {
            issuerNif?.let(NifValidator::normalizeTaxId) -> InvoiceType.ISSUED
            recipientNif?.let(NifValidator::normalizeTaxId) -> InvoiceType.RECEIVED
            else -> null
        }
    }
}
