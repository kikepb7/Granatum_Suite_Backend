package com.granatum.core.infrastructure.database.mappers

import com.granatum.core.domain.model.ShiftModel
import com.granatum.core.domain.model.ShiftBreakModel
import com.granatum.core.domain.model.ShiftLocationModel
import com.granatum.core.infrastructure.database.entities.ShiftEntity
import com.granatum.core.infrastructure.database.entities.ShiftBreakEntity
import com.granatum.core.infrastructure.database.entities.ShiftLocationEmbeddable

/**
 * Entity -> Model.
 *
 * Touches `breaks` and `employee`, both lazy, so this **must** be called inside
 * the transaction. `spring.jpa.open-in-view` is false, so mapping after the
 * service has returned throws LazyInitializationException - which this project
 * has already hit once, with `MaterialEntity.fotos`.
 */
fun ShiftEntity.toModel(): ShiftModel = ShiftModel(
    id = id,
    employeeId = employee.id,
    clockIn = clockIn,
    clockOut = clockOut,
    status = status,
    workedMinutes = workedMinutes,
    wasIncomplete = wasIncomplete,
    clockInLocation = clockInLocation?.toModel(),
    clockOutLocation = clockOutLocation?.toModel(),
    breaks = breaks.sortedBy { it.startedAt }.map { it.toModel() }
)

fun ShiftBreakEntity.toModel(): ShiftBreakModel = ShiftBreakModel(
    id = id,
    type = type,
    start = startedAt,
    end = endedAt
)

fun ShiftLocationEmbeddable.toModel(): ShiftLocationModel = ShiftLocationModel(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters
)
