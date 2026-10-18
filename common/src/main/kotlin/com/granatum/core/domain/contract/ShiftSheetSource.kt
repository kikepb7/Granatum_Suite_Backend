package com.granatum.core.domain.contract

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * The shifts of a month as the Google Sheets copy writes them (feature 012,
 * US2, research.md D-002).
 *
 * `timetracking` implements it, reusing the read of the CSV export: one
 * read-only `REPEATABLE READ` snapshot, in batches, with the current values
 * (approved corrections are already applied to the shifts). `sheets` uses it
 * and never mentions `timetracking` (constitution principle I).
 *
 * **Reads only**: implementations write nothing to the register (FR-013).
 * Never call it inside a transaction that must stay short: it opens its own.
 */
interface ShiftSheetSource {

    /**
     * Every shift whose clock-in falls in [month] in Europe/Madrid, of the whole
     * staff, people who have left included. A shift crossing midnight belongs to
     * the day - and the month - of its clock-in. Ordered by date and clock-in.
     */
    fun month(month: YearMonth): ShiftSheetMonth

    /**
     * The first civil date the legal retention period still covers: a month
     * that ends before it cannot be exported (`400 INVALID_PERIOD`), like the
     * CSV export refuses a range before it.
     */
    fun retentionCutoff(): LocalDate
}

/**
 * The rows of a month and how many **distinct** people in them have no last
 * name yet (FR-014).
 */
data class ShiftSheetMonth(
    val rows: List<ShiftSheetRow>,
    val lastNamesPending: Int
) {
    override fun toString(): String = "ShiftSheetMonth(rows=${rows.size}, lastNamesPending=$lastNamesPending)"
}

/**
 * One shift, in Europe/Madrid. [lastName] is null while pending (FR-003);
 * [clockOut] is null for an open or `INCOMPLETE` shift (FR-011).
 *
 * `toString` hides the name and the identity document: they are personal data
 * and a data class would print them in any log line (principle VI).
 */
data class ShiftSheetRow(
    val name: String,
    val lastName: String?,
    val identityDocument: String,
    val date: LocalDate,
    val clockIn: LocalTime,
    val clockOut: LocalTime?
) {
    override fun toString(): String = "ShiftSheetRow(date=$date, clockIn=$clockIn, clockOut=$clockOut)"
}
