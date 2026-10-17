package com.granatum.core.domain.model

import com.granatum.core.domain.type.ShiftStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Domain view of a working day: independent of JPA and of HTTP. Services return
 * this; controllers map it to a DTO and never see the entity.
 */
data class ShiftModel(
    val id: UUID,
    val employeeId: UUID,
    val clockIn: Instant,
    val clockOut: Instant?,
    val status: ShiftStatus,
    val workedMinutes: Int?,
    val wasIncomplete: Boolean,
    val clockInLocation: ShiftLocationModel?,
    val clockOutLocation: ShiftLocationModel?,
    val breaks: List<ShiftBreakModel>
)

data class ShiftLocationModel(
    val latitude: BigDecimal,
    val longitude: BigDecimal,
    val accuracyMeters: Int?
)
