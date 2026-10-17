package com.granatum.core.api.controllers

import com.granatum.core.api.dto.RegisterExportDto
import com.granatum.core.api.dto.ExportVerificationDto
import com.granatum.core.api.dto.toDto
import com.granatum.core.service.RegisterExportLog
import com.granatum.core.service.RegisterExportVerification
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.InputStream
import java.time.LocalDate
import java.util.UUID

/**
 * Who exported what, and whether a file is one of ours (FR-028, FR-029).
 * `ADMIN` only, by the `/api/exports` rule in `SecurityConfig` (D-014).
 */
@RestController
@RequestMapping("/api/exports")
class RegisterExportAuditController(
    private val exportLog: RegisterExportLog,
    private val verification: RegisterExportVerification
) {

    /** Every filter optional and combinable; newest first. */
    @GetMapping
    fun search(
        @RequestParam(required = false) employeeId: UUID?,
        @RequestParam(required = false) generatedFrom: LocalDate?,
        @RequestParam(required = false) generatedTo: LocalDate?,
        @RequestParam(required = false) coversFrom: LocalDate?,
        @RequestParam(required = false) coversTo: LocalDate?
    ): List<RegisterExportDto> =
        exportLog.search(employeeId, generatedFrom, generatedTo, coversFrom, coversTo).map { it.toDto() }

    /**
     * The file is the raw request body, taken as an [InputStream] and never as
     * a DTO or a multipart part (D-007): nothing deserialises it, nothing logs
     * it, nothing writes it to disk. It is hashed as it arrives and dropped.
     */
    @PostMapping(
        "/verify",
        consumes = ["text/csv", MediaType.APPLICATION_OCTET_STREAM_VALUE]
    )
    fun verify(file: InputStream): ExportVerificationDto {
        val r = verification.verify(file)
        return ExportVerificationDto(
            fingerprint = r.fingerprint,
            matches = r.matchingExports.isNotEmpty(),
            exports = r.matchingExports.map { it.toDto() }
        )
    }
}
