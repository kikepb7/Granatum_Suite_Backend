package com.granatum.core.domain.model

import com.granatum.core.domain.type.BreakType
import java.time.Instant
import java.util.UUID

data class ShiftBreakModel(
    val id: UUID,
    val type: BreakType,
    val start: Instant,
    val end: Instant?
)
