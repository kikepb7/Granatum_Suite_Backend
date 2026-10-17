package com.granatum.core.api.mappers

import com.granatum.core.api.dto.ShiftSummaryDayDto
import com.granatum.core.api.dto.MonthlyShiftSummaryDto
import com.granatum.core.domain.model.ShiftSummaryDay
import com.granatum.core.domain.model.MonthlyShiftSummary

fun MonthlyShiftSummary.toDto(): MonthlyShiftSummaryDto = MonthlyShiftSummaryDto(
    employeeId = employeeId,
    year = year,
    month = month,
    contractType = contractType,
    totalWorkedMinutes = totalWorkedMinutes,
    days = days.map { it.toDto() }
)

fun ShiftSummaryDay.toDto(): ShiftSummaryDayDto = ShiftSummaryDayDto(
    date = date,
    clockIn = clockIn,
    clockOut = clockOut,
    workedMinutes = workedMinutes,
    breakMinutes = breakMinutes,
    reconstructed = reconstructed,
    corrected = corrected
)
