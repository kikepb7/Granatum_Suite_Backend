package com.granatum.core.domain.type

/** The four operations recorded in the append-only event log. */
enum class ShiftOperationType {
    CLOCK_IN,
    BREAK_START,
    BREAK_END,
    CLOCK_OUT
}
