package com.granatum.core.api.controllers

import com.granatum.core.api.dto.toDto
import com.granatum.core.domain.exception.InvalidReportPeriodException
import com.granatum.core.infrastructure.reports.InvoicingReportCsvWriter
import com.granatum.core.infrastructure.reports.InvoicingReportPdfWriter
import com.granatum.core.service.InvoicingReports
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayOutputStream

/** `GET /api/invoicing/reports` (FR-018 to FR-023): JSON, CSV or PDF, with the same figures. */
@RestController
@RequestMapping("/api/invoicing/reports")
class InvoicingReportController(private val reports: InvoicingReports) {

    @GetMapping
    fun report(
        @RequestParam period: String,
        @RequestParam year: Int,
        @RequestParam(required = false) month: Int?,
        @RequestParam(required = false) quarter: Int?,
        @RequestParam(defaultValue = "json") format: String
    ): ResponseEntity<*> {
        // A missing month or quarter is a missing parameter (400 VALIDATION); an
        // impossible one is INVALID_PERIOD (contracts/README.md).
        when (period.uppercase()) {
            "MONTHLY" -> if (month == null) throw MissingServletRequestParameterException("month", "Integer")
            "QUARTERLY" -> if (quarter == null) throw MissingServletRequestParameterException("quarter", "Integer")
        }
        val p = reports.period(period, year, month, quarter)
        val report = reports.calculate(p)
        return when (format.lowercase()) {
            "json" -> ResponseEntity.ok(report.toDto())
            "csv" -> file(
                ByteArrayOutputStream().also { InvoicingReportCsvWriter.write(report, it) }.toByteArray(),
                MediaType("text", "csv", Charsets.UTF_8), InvoicingReportCsvWriter.fileName(p)
            )
            "pdf" -> file(
                ByteArrayOutputStream().also { InvoicingReportPdfWriter.write(report, it) }.toByteArray(),
                MediaType.APPLICATION_PDF, InvoicingReportPdfWriter.fileName(p)
            )
            else -> throw InvalidReportPeriodException("El formato es json, csv o pdf")
        }
    }

    private fun file(bytes: ByteArray, type: MediaType, name: String): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
            .body(bytes)
}
