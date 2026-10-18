package com.granatum.core.api.controllers

import com.granatum.core.api.dto.SheetQueueStatusDto
import com.granatum.core.api.dto.SheetRetryAllDto
import com.granatum.core.api.dto.SheetSettingsDto
import com.granatum.core.api.dto.SheetTaskCreatedDto
import com.granatum.core.api.dto.SheetTaskDto
import com.granatum.core.api.dto.UpdateSheetSettingsRequest
import com.granatum.core.api.dto.toDto
import com.granatum.core.api.util.requestUserId
import com.granatum.core.scheduling.SheetTaskWorker
import com.granatum.core.service.SheetExports
import com.granatum.core.service.SheetSettingsService
import com.granatum.core.service.SheetTaskQueue
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The Google Sheets copy (feature 012, contracts/README.md): settings, the
 * exports an ADMIN asks for, status and retries. `ADMIN` only, by the `/api/sheets` rule of `SecurityConfig`.
 */
@RestController
@RequestMapping("/api/sheets")
class SheetsController(
    private val settings: SheetSettingsService,
    private val queue: SheetTaskQueue,
    private val worker: SheetTaskWorker,
    private val exports: SheetExports
) {

    @GetMapping("/settings")
    fun settings(): SheetSettingsDto = settings.view().toDto()

    /** Checks that both spreadsheets can be edited before saving (research.md D-007). */
    @PutMapping("/settings")
    fun updateSettings(@Valid @RequestBody request: UpdateSheetSettingsRequest): SheetSettingsDto =
        settings.save(request.shiftsSpreadsheet, request.invoicesSpreadsheet, requestUserId).toDto()

    @GetMapping("/status")
    fun status(): SheetQueueStatusDto = queue.status().toDto()

    @GetMapping("/tasks/{id}")
    fun task(@PathVariable id: UUID): SheetTaskDto = queue.find(id).toDto()

    @PostMapping("/tasks/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retry(@PathVariable id: UUID): SheetTaskDto {
        val task = queue.retry(id)
        worker.kick()
        return task.toDto()
    }

    @PostMapping("/tasks/retry-failed")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retryAllFailed(): SheetRetryAllDto {
        val requeued = queue.retryAllFailed()
        if (requeued > 0) worker.kick()
        return SheetRetryAllDto(requeued)
    }

    /**
     * Exports a month of shifts: rewrites the tab `YYYY-MM` of the shifts
     * spreadsheet (US2). Queues the task - or joins a pending one of the same
     * month - and kicks the worker once it is committed.
     */
    @PostMapping("/shifts/{year}/{month}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun exportShifts(@PathVariable year: Int, @PathVariable month: Int): SheetTaskCreatedDto {
        val id = exports.exportShifts(year, month, requestUserId)
        worker.kick()
        return SheetTaskCreatedDto(id)
    }

    /**
     * Rewrites the month's receipts tab with every confirmed invoice issued in
     * it (US3, FR-018). Same flow as [exportShifts].
     */
    @PostMapping("/invoices/{year}/{month}/resync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun resyncInvoices(@PathVariable year: Int, @PathVariable month: Int): SheetTaskCreatedDto {
        val id = exports.resyncInvoices(year, month, requestUserId)
        worker.kick()
        return SheetTaskCreatedDto(id)
    }
}
