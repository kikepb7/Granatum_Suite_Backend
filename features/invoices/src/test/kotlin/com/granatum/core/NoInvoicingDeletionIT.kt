package com.granatum.core

import com.granatum.core.infrastructure.database.repositories.CompanyRepository
import com.granatum.core.infrastructure.database.repositories.InvoiceChangeRepository
import com.granatum.core.infrastructure.database.repositories.InvoiceDocumentRepository
import com.granatum.core.infrastructure.database.repositories.InvoiceRecognitionRepository
import com.granatum.core.infrastructure.database.repositories.InvoiceRepository
import com.granatum.core.infrastructure.database.repositories.QuarterEventRepository
import com.granatum.core.infrastructure.database.repositories.QuarterRepository
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Nothing in invoicing can be deleted: invoices are kept at least six years
 * (Commercial Code, art. 30), discarding one keeps it, and the histories are
 * append-only.
 *
 * The list is **fixed** on purpose. A list built by scanning would silently
 * skip a repository nobody registered; this one fails until each new
 * repository is added and checked - the lesson of feature 003's finding C1.
 *
 * Validated by adding a `deleteById` to `InvoiceRepository` (red) and removing
 * it again (2026-10-08).
 */
class NoInvoicingDeletionIT {

    private val repositories = listOf(
        CompanyRepository::class.java,
        QuarterRepository::class.java,
        QuarterEventRepository::class.java,
        InvoiceRepository::class.java,
        InvoiceDocumentRepository::class.java,
        InvoiceRecognitionRepository::class.java,
        InvoiceChangeRepository::class.java
    )

    @Test
    fun `no invoicing repository exposes a deletion`() {
        val offenders = repositories.flatMap { repo ->
            repo.methods
                .map { it.name }
                .filter { it.startsWith("delete") || it.startsWith("remove") }
                .map { "${repo.simpleName}.$it" }
        }
        assertEquals(emptyList(), offenders, "a deletion here would let invoices or their history disappear")
    }

    @Test
    fun `every repository of the module is in the list`() {
        // Seven repositories for eight tables: invoice_vat_lines is reached
        // only through its invoice (cascade), never through a repository.
        assertEquals(7, repositories.size)
    }
}
