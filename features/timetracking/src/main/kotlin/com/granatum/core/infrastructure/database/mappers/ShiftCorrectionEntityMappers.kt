package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.ShiftCorrectionModel
import com.granatum.core.infrastructure.database.ShiftValuesJson
import com.granatum.core.infrastructure.database.entities.ShiftCorrectionRequestEntity

/**
 * Entity -> Model, deserialising the two JSONB documents through
 * [ShiftValuesJson] - the module's own mapper, deliberately not the web
 * layer's. See that class for why.
 */
fun ShiftCorrectionRequestEntity.toModel(json: ShiftValuesJson): ShiftCorrectionModel =
    ShiftCorrectionModel(
        id = id,
        shiftId = shift.id,
        requesterId = requesterId,
        reason = reason,
        status = status,
        proposedValues = json.read(proposedValues),
        originalValues = originalValues?.let { json.read(it) },
        resolvedById = resolvedById,
        resolvedAt = resolvedAt,
        resolutionReason = resolutionReason,
        createdAt = createdAt
    )
