package com.granatum.core.infrastructure.database

import com.granatum.core.domain.model.Invoice
import com.granatum.core.domain.model.VatLine
import com.granatum.core.domain.model.InvoiceParty
import com.granatum.core.infrastructure.database.entities.InvoiceEntity

fun InvoiceEntity.toDomain(): Invoice = Invoice(
    id = id,
    status = status,
    type = type,
    issuer = if (issuerName == null && issuerNif == null) null else InvoiceParty(issuerName, issuerNif),
    recipient = if (recipientName == null && recipientNif == null) null else InvoiceParty(recipientName, recipientNif),
    number = number,
    issueDate = issueDate,
    concept = concept,
    currency = currency,
    vatLines = vatLines.map { VatLine(it.vatRate, it.base, it.vatAmount, it.surcharge, it.noVatCause) },
    withholdings = withholdings,
    total = total,
    corrective = corrective,
    version = version
)
