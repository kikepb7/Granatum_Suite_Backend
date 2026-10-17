package com.granatum.core.api.controllers

import com.granatum.core.api.util.requestUserId
import com.granatum.core.api.util.requestUserRole
import com.granatum.core.domain.exception.InconsistentValuesException
import com.granatum.core.domain.model.ExportTarget
import com.granatum.core.domain.type.Role
import com.granatum.core.service.PreparedRegisterExport
import com.granatum.core.service.RegisterExportService
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.time.LocalDate
import java.util.UUID

/**
 * Downloads of the register as a file (contracts/README.md of feature 003).
 *
 * Covered by the existing rule for GET under `/api/shifts`, open to the four roles;
 * who may export whom is decided in the service against the token subject.
 *
 * ## Why everything is settled before the body
 *
 * A [StreamingResponseBody] runs on another thread once the status line has
 * gone out. Two consequences:
 *
 * - The token's subject and role are read **here**, on the request thread. The
 *   `SecurityContext` is not on the streaming thread.
 * - Validation, ownership and the effective range are settled by
 *   [RegisterExportService.prepare] before returning, so a refusal is still a
 *   proper `4xx` and not a `200` with half a file.
 *
 * The file name carries the person's id and the effective range, **never the
 * name**: `Content-Disposition` can end up in an access log (D-017).
 */
@RestController
@RequestMapping("/api/shifts")
class RegisterExportController(
    private val exportService: RegisterExportService
) {
    companion object {
        /** D-009: present only when the range was trimmed, saying where data starts. */
        const val AVAILABLE_FROM = "X-Records-Available-From"
        private val CSV = MediaType("text", "csv", Charsets.UTF_8)
    }

    /**
     * `employeeId` omitted means "myself" for an EMPLOYEE and the whole staff
     * for everyone else (FR-013, FR-014).
     */
    @GetMapping("/export")
    fun export(
        @RequestParam(defaultValue = "csv") format: String,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
        @RequestParam(required = false) employeeId: UUID?
    ): ResponseEntity<StreamingResponseBody> {
        // D-015: one format. Anything else is refused rather than silently
        // answered with CSV under a name the caller did not ask for.
        if (format != "csv") throw InconsistentValuesException("formato no admitido; solo csv")

        val requester = requestUserId
        val role = requestUserRole
        val target = when {
            employeeId != null -> ExportTarget.Person(employeeId)
            role == Role.EMPLOYEE -> ExportTarget.Person(requester)
            else -> ExportTarget.Staff
        }

        val prepared = exportService.prepare(target, from, to, requester, role)
        val who = (target as? ExportTarget.Person)?.employeeId?.toString() ?: "plantilla"
        val name = "registro-jornada_${who}_${prepared.range.from}_${prepared.range.to}.csv"

        return download(name, prepared)
    }

    /**
     * One person's calendar month with its total (FR-019 to FR-023). The month
     * in the name is zero-padded so the files of a year sort in order.
     */
    @GetMapping("/employee/{employeeId}/summary/download")
    fun downloadMonthly(
        @PathVariable employeeId: UUID,
        @RequestParam year: Int,
        @RequestParam month: Int
    ): ResponseEntity<StreamingResponseBody> {
        val prepared = exportService.prepareMonthly(employeeId, year, month, requestUserId, requestUserRole)
        val name = "registro-mensual_${employeeId}_$year-${month.toString().padStart(2, '0')}.csv"
        return download(name, prepared)
    }

    private fun download(name: String, prepared: PreparedRegisterExport): ResponseEntity<StreamingResponseBody> {
        val range = prepared.range
        val headers = HttpHeaders().apply {
            contentType = CSV
            contentDisposition = ContentDisposition.attachment().filename(name).build()
            if (range.trimmed) set(AVAILABLE_FROM, range.from.toString())
        }
        return ResponseEntity.ok()
            .headers(headers)
            .body(StreamingResponseBody { output -> exportService.write(prepared, output) })
    }
}
