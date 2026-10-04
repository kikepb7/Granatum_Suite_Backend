package com.granatum.core.domain.type

/**
 * Lifecycle of a working day.
 *
 * - [EN_CURSO]: open. Exactly one per empleado at a time, enforced by a partial
 *   unique index rather than by service-side checks alone.
 * - [CERRADO]: exit recorded and worked minutes computed.
 * - [INCOMPLETO]: the exit was never recorded. Set by the daily retention-style
 *   job for fichajes still open from a previous day. It no longer counts as a
 *   day in progress, so it does not block the person from clocking in again.
 */
enum class EstadoFichaje {
    EN_CURSO,
    CERRADO,
    INCOMPLETO
}
