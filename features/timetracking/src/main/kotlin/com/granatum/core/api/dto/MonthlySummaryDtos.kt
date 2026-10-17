package com.granatum.core.api.dto

import com.granatum.core.domain.type.ContractType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class MonthlyShiftSummaryDto(
    val employeeId: UUID,
    val year: Int,
    val month: Int,
    val contractType: ContractType,
    val totalWorkedMinutes: Int,
    val days: List<ShiftSummaryDayDto>
)

data class ShiftSummaryDayDto(
    val date: LocalDate,
    val clockIn: Instant,
    val clockOut: Instant?,
    val workedMinutes: Int?,
    val breakMinutes: Int,
    val reconstructed: Boolean,
    val corrected: Boolean
)
