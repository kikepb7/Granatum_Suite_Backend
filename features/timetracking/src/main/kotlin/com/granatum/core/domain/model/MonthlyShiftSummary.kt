package com.granatum.core.domain.model

import com.granatum.core.domain.type.ContractType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A month of someone's register, aggregated.
 *
 * The **calculation** lives in this module because it applies its domain rules:
 * what counts as worked time, how approved corrections affect it, which days
 * were reconstructed. The **delivery** - download, file format, sending cadence
 * - belongs to the export feature. Putting CSV generation here would duplicate
 * what that feature owns; putting this aggregation there would force the export
 * module to know the shift rules, which is the worse of the two couplings.
 */
data class MonthlyShiftSummary(
    val employeeId: UUID,
    val year: Int,
    val month: Int,
    /**
     * Carried because article 12.4.c obliges an employer to hand part-time
     * staff a monthly summary with their payslip, so the consumer needs to know
     * whether this month's summary is one of those.
     */
    val contractType: ContractType,
    val totalWorkedMinutes: Int,
    val days: List<ShiftSummaryDay>
)

data class ShiftSummaryDay(
    val date: LocalDate,
    val clockIn: Instant,
    val clockOut: Instant?,
    val workedMinutes: Int?,
    val breakMinutes: Int,
    /** The exit was added after the fact: `wasIncomplete` on the fichaje. */
    val reconstructed: Boolean,
    /** At least one approved correction touched this day. */
    val corrected: Boolean
)
