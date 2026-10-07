package com.granatum.core.domain.type

/**
 * What an export covered, as recorded in `exportaciones.alcance`.
 *
 * Flat on purpose, unlike `AlcanceExportacion`: the audit row stores the kind
 * and the person separately, and a sealed type does not map to one column.
 */
enum class AlcanceRegistro {
    PERSONA,
    PLANTILLA,
    MENSUAL
}
