package com.granatum.core.api.controllers

import com.granatum.core.api.dto.ShiftCorrectionDto
import com.granatum.core.api.dto.CreateShiftCorrectionRequest
import com.granatum.core.api.dto.RejectShiftCorrectionRequest
import com.granatum.core.api.mappers.toDto
import com.granatum.core.api.mappers.toModel
import com.granatum.core.api.util.requestUserId
import com.granatum.core.domain.exception.LocationNotCorrectableException
import com.granatum.core.api.util.requestUserRole
import com.granatum.core.domain.type.CorrectionStatus
import com.granatum.core.service.ShiftCorrectionService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Correction requests: the only route by which a finalised fichaje changes
 * value.
 *
 * Mapped under two prefixes on purpose - requests belong to a fichaje, while
 * resolving one is an action on the request itself, so forcing both under the
 * same path would make the URLs read worse than the model behind them.
 */
@RestController
class ShiftCorrectionController(
    private val correctionService: ShiftCorrectionService
) {

    @PostMapping("/api/shifts/{id}/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    fun request(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CreateShiftCorrectionRequest
    ): ShiftCorrectionDto {
        // Refused, not ignored (FR-020a). Ignoring it would return 201 to a
        // client convinced it had corrected the location, and the divergence
        // would surface only much later - if ever.
        if (request.proposedValues.location != null) {
            throw LocationNotCorrectableException()
        }

        return correctionService.request(
            shiftId = id,
            requesterId = requestUserId,
            role = requestUserRole,
            reason = request.reason,
            proposed = request.proposedValues.toModel()
        ).toDto()
    }

    @PostMapping("/api/corrections/{id}/approve")
    fun approve(@PathVariable id: UUID): ShiftCorrectionDto =
        correctionService.approve(
            requestId = id,
            resolverId = requestUserId,
            role = requestUserRole
        ).toDto()

    @PostMapping("/api/corrections/{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RejectShiftCorrectionRequest
    ): ShiftCorrectionDto =
        correctionService.reject(
            requestId = id,
            resolverId = requestUserId,
            role = requestUserRole,
            resolutionReason = request.resolutionReason
        ).toDto()

    /**
     * A fichaje's correction history.
     *
     * Not in the original brief, and added because without it the original
     * values are stored but unreadable through the API: SC-003 ("the original
     * stays recoverable") would not be verifiable from outside, and the export
     * feature needs it for its "corrections applied" column.
     */
    @GetMapping("/api/shifts/{id}/corrections")
    fun history(@PathVariable id: UUID): List<ShiftCorrectionDto> =
        correctionService.findByShift(id).map { it.toDto() }

    /** The reviewer's queue. Also absent from the brief; the flow is unusable without it. */
    @GetMapping("/api/corrections")
    fun byStatus(
        @RequestParam(defaultValue = "PENDING") status: CorrectionStatus
    ): List<ShiftCorrectionDto> =
        correctionService.findByStatus(status).map { it.toDto() }
}
