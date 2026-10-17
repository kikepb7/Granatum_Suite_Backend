package com.granatum.core.domain.type

/**
 * What an export covered, as recorded in `exports.scope`.
 *
 * Flat on purpose, unlike `ExportTarget`: the audit row stores the kind
 * and the person separately, and a sealed type does not map to one column.
 */
enum class ExportScope {
    PERSON,
    STAFF,
    MONTHLY
}
