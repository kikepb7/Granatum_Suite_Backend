package com.granatum.core

import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.model.RecognizedProposal
import com.granatum.core.service.CompanyService
import com.granatum.core.service.InvoiceReader
import com.granatum.core.service.UploadedInvoiceFile
import com.granatum.core.service.InvoiceUploads
import java.util.UUID
import javax.sql.DataSource

/**
 * Builds invoices for integration tests through the real upload path, with the
 * recognition double answering what each test needs.
 */
class InvoiceScenario(
    private val uploads: InvoiceUploads,
    private val reader: InvoiceReader,
    private val fake: FakeInvoiceRecognizer,
    private val company: CompanyService,
    private val dataSource: DataSource
) {
    val admin: UUID = UUID.randomUUID()

    fun configureCompany() = company.save("Floristeria Granatum S.L.", "B12345674", admin)

    /** A draft recognised from a fresh file, with a unique number unless told otherwise. */
    fun draft(proposal: RecognizedProposal = FakeInvoiceRecognizer.proposal(number = "F-${UUID.randomUUID().toString().take(8)}")): UUID {
        fake.script = { FakeInvoiceRecognizer.recognized(proposal) }
        val id = uploads.upload(listOf(UploadedInvoiceFile(null, InvoiceSamples.pdf())), admin).single().invoiceId!!
        waitFor(id, InvoiceStatus.DRAFT)
        return id
    }

    fun waitFor(id: UUID, status: InvoiceStatus) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < deadline) {
            if (reader.status(id) == status) return
            Thread.sleep(30)
        }
        throw AssertionError("la factura no llego a $status; esta en ${reader.status(id)}")
    }

    fun text(sql: String, vararg parameters: Any): String? = dataSource.connection.use { c ->
        c.prepareStatement(sql).use { s ->
            parameters.forEachIndexed { i, p -> s.setObject(i + 1, p) }
            s.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
        }
    }

    fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
}
