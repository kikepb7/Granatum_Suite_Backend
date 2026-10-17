package com.granatum.core.api.controllers

import com.granatum.core.api.dto.InvoiceDto
import com.granatum.core.api.dto.InvoiceRequest
import com.granatum.core.api.dto.InvoiceUploadResultDto
import com.granatum.core.api.dto.InvoiceVersionRequest
import com.granatum.core.domain.model.InvoiceStatus
import com.granatum.core.domain.model.VatLine
import com.granatum.core.domain.model.InvoiceType
import com.granatum.core.service.InvoiceQueries
import com.granatum.core.service.InvoiceFilter
import com.granatum.core.service.InvoiceHistory
import com.granatum.core.service.InvoicePage
import com.granatum.core.service.InvoiceSummary
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestParam
import com.granatum.core.service.InvoiceReview
import com.granatum.core.service.InvoiceValues
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import java.math.BigDecimal
import com.granatum.core.api.util.requestUserId
import com.granatum.core.service.InvoiceDetails
import com.granatum.core.service.UploadedInvoiceFile
import com.granatum.core.service.InvoiceUploads
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/** Invoices: upload, review, originals. `ADMIN` only, by the `/api/invoicing` rule. */
@RestController
@RequestMapping("/api/invoicing/invoices")
class InvoiceController(
    private val uploads: InvoiceUploads,
    private val details: InvoiceDetails,
    private val review: InvoiceReview,
    private val queries: InvoiceQueries
) {

    /**
     * One or more files in the `files` part. `202`: the accepted ones are
     * recognised in the background (research.md D-005). Each result is
     * identified by position, never by file name (contracts/README.md).
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun upload(@RequestPart("files") files: List<MultipartFile>): List<InvoiceUploadResultDto> =
        uploads.upload(files.map { UploadedInvoiceFile(it.originalFilename, it.bytes) }, requestUserId)
            .map { InvoiceUploadResultDto(it.file, it.result, it.invoiceId) }

    /** FR-024: filters optional and combinable, newest first, paginated. */
    @GetMapping
    fun list(
        @RequestParam(required = false) from: java.time.LocalDate?,
        @RequestParam(required = false) to: java.time.LocalDate?,
        @RequestParam(required = false) party: String?,
        @RequestParam(required = false) type: InvoiceType?,
        @RequestParam(required = false) status: InvoiceStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int
    ): InvoicePage<InvoiceSummary> = queries.list(InvoiceFilter(from, to, party, type, status, page, size))

    /** FR-025: the original byte for byte, named by the invoice's id. */
    @GetMapping("/{id}/original")
    fun original(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val o = queries.original(id)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(o.document.mediaType))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(o.name).build().toString())
            .body(o.document.content)
    }

    @GetMapping("/{id}/history")
    fun history(@PathVariable id: UUID): InvoiceHistory = queries.history(id)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): InvoiceDto = details.build(id)

    @PutMapping("/{id}")
    fun correct(@PathVariable id: UUID, @Valid @RequestBody r: InvoiceRequest): InvoiceDto {
        review.correct(id, r.toValues(), r.version, requestUserId)
        return details.build(id)
    }

    @PostMapping("/{id}/confirm")
    fun confirm(@PathVariable id: UUID, @RequestBody r: InvoiceVersionRequest): InvoiceDto {
        review.confirm(id, r.version, requestUserId)
        return details.build(id)
    }

    @PostMapping("/{id}/discard")
    fun discard(@PathVariable id: UUID, @RequestBody r: InvoiceVersionRequest): InvoiceDto {
        review.discard(id, r.version, requestUserId)
        return details.build(id)
    }

    @PostMapping("/{id}/recognize")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun recognize(@PathVariable id: UUID) = uploads.retry(id)

    private fun InvoiceRequest.toValues() = InvoiceValues(
        type = type,
        issuerName = issuer?.name, issuerNif = issuer?.nif,
        recipientName = recipient?.name, recipientNif = recipient?.nif,
        number = number, issueDate = issueDate, concept = concept, currency = currency,
        vatLines = vatLines.map { VatLine(BigDecimal(it.vatRate).setScale(2), BigDecimal(it.base).setScale(2), BigDecimal(it.vatAmount).setScale(2), BigDecimal(it.surcharge).setScale(2), it.noVatCause) },
        withholdings = BigDecimal(withholdings).setScale(2),
        total = total?.let { BigDecimal(it).setScale(2) },
        corrective = corrective
    )
}
