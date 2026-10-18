package com.granatum.core.api.dto

import com.granatum.core.domain.model.SheetQueueStatus
import com.granatum.core.domain.model.SheetTask
import com.granatum.core.domain.model.SheetTaskKind
import com.granatum.core.domain.model.SheetTaskStatus
import com.granatum.core.service.SheetSettingsService
import com.granatum.core.service.SheetSettingsView
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** `GET /api/sheets/settings` (contracts/README.md). */
data class SheetSettingsDto(
    val available: Boolean,
    val serviceAccountEmail: String?,
    val shiftsSpreadsheetId: String?,
    val shiftsSpreadsheetUrl: String?,
    val invoicesSpreadsheetId: String?,
    val invoicesSpreadsheetUrl: String?,
    val configuredAt: Instant?,
    val configuredBy: UUID?
)

/** `PUT /api/sheets/settings`: a full link or a bare id for each. */
data class UpdateSheetSettingsRequest(
    @field:NotBlank @field:Size(max = 500) val shiftsSpreadsheet: String,
    @field:NotBlank @field:Size(max = 500) val invoicesSpreadsheet: String
)

/** `GET /api/sheets/tasks/{id}` and the items of the status. */
data class SheetTaskDto(
    val id: UUID,
    val kind: SheetTaskKind,
    val reference: String,
    val status: SheetTaskStatus,
    val attempts: Int,
    val rowCount: Int?,
    val lastNamesPending: Int?,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?
)

/** `GET /api/sheets/status`. */
data class SheetQueueStatusDto(
    val lastSuccessAt: Instant?,
    val pending: Long,
    val failed: Long,
    val recent: List<SheetTaskDto>
)

/** `202` of an export or a resync: the task to follow with `GET /api/sheets/tasks/{id}`. */
data class SheetTaskCreatedDto(val taskId: UUID)

/** `POST /api/sheets/tasks/retry-failed`. */
data class SheetRetryAllDto(val requeued: Int)

fun SheetSettingsView.toDto(): SheetSettingsDto {
    val s = settings
    return SheetSettingsDto(
        available = available,
        serviceAccountEmail = serviceAccountEmail,
        shiftsSpreadsheetId = s?.shiftsSpreadsheetId,
        shiftsSpreadsheetUrl = s?.shiftsSpreadsheetId?.let { SheetSettingsService.url(it) },
        invoicesSpreadsheetId = s?.invoicesSpreadsheetId,
        invoicesSpreadsheetUrl = s?.invoicesSpreadsheetId?.let { SheetSettingsService.url(it) },
        configuredAt = s?.configuredAt,
        configuredBy = s?.configuredBy
    )
}

fun SheetTask.toDto() = SheetTaskDto(
    id, kind, reference, status, attempts, rowCount, lastNamesPending, lastError, createdAt, updatedAt, completedAt
)

fun SheetQueueStatus.toDto() = SheetQueueStatusDto(lastSuccessAt, pending, failed, recent.map { it.toDto() })
