package com.granatum.core.domain.type

/**
 * Where an export of the register went, as recorded in `exports.destination`
 * (feature 012, research.md D-006).
 */
enum class ExportDestination {
    /** A CSV file handed over by the API (feature 003). */
    CSV,

    /** The month's tab of the company's Google spreadsheet (feature 012). */
    GOOGLE_SHEETS
}
