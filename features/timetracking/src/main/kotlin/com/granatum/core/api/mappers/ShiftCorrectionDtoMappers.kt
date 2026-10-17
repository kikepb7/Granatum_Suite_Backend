package com.granatum.core.api.mappers

import com.granatum.core.api.dto.ShiftCorrectionDto
import com.granatum.core.api.dto.ShiftValuesDto
import com.granatum.core.api.dto.BreakValuesDto
import com.granatum.core.domain.model.ShiftCorrectionModel
import com.granatum.core.domain.model.ShiftValues
import com.granatum.core.domain.model.BreakValues

fun ShiftCorrectionModel.toDto(): ShiftCorrectionDto = ShiftCorrectionDto(
    id = id,
    shiftId = shiftId,
    requesterId = requesterId,
    reason = reason,
    status = status,
    proposedValues = proposedValues.toDto(),
    originalValues = originalValues?.toDto(),
    resolvedById = resolvedById,
    resolvedAt = resolvedAt,
    resolutionReason = resolutionReason,
    createdAt = createdAt
)

fun ShiftValues.toDto(): ShiftValuesDto = ShiftValuesDto(
    clockIn = clockIn,
    clockOut = clockOut,
    breaks = breaks.map { it.toDto() }
)

fun BreakValues.toDto(): BreakValuesDto = BreakValuesDto(
    type = type,
    start = start,
    end = end
)

fun ShiftValuesDto.toModel(): ShiftValues = ShiftValues(
    clockIn = clockIn,
    clockOut = clockOut,
    breaks = breaks.map { BreakValues(it.type, it.start, it.end) }
)
