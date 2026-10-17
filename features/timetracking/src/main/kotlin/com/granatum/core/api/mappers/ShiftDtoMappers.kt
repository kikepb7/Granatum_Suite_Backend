package com.granatum.core.api.mappers

import com.granatum.core.domain.model.ShiftModel
import com.granatum.core.domain.model.ShiftBreakModel
import com.granatum.core.domain.model.ShiftLocationModel
import com.granatum.core.api.dto.ShiftDto
import com.granatum.core.api.dto.ShiftBreakDto
import com.granatum.core.api.dto.ShiftLocationDto

/**
 * Model -> Dto.
 *
 * [includeLocation] exists for the REPRESENTATIVE role: article 34.9 entitles
 * worker representatives to the register, but not to where each person clocked
 * in, which is the most intrusive datum in it. Showing it would be processing
 * personal data with no obligation behind it.
 *
 * The flag drops the fields rather than nulling them, because null already
 * means "recorded without a location" and reusing it here would make the two
 * cases indistinguishable.
 */
fun ShiftModel.toDto(includeLocation: Boolean = true): ShiftDto = ShiftDto(
    id = id,
    employeeId = employeeId,
    clockIn = clockIn,
    clockOut = clockOut,
    status = status,
    workedMinutes = workedMinutes,
    wasIncomplete = wasIncomplete,
    clockInLocation = if (includeLocation) clockInLocation?.toDto() else null,
    clockOutLocation = if (includeLocation) clockOutLocation?.toDto() else null,
    breaks = breaks.map { it.toDto() }
)

fun ShiftBreakModel.toDto(): ShiftBreakDto = ShiftBreakDto(
    id = id,
    type = type,
    start = start,
    end = end
)

fun ShiftLocationModel.toDto(): ShiftLocationDto = ShiftLocationDto(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters
)
