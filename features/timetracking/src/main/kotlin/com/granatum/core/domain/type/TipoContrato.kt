package com.granatum.core.domain.type

/**
 * Contract type. [PARCIAL] carries an extra legal obligation: article 12.4.c of
 * the Spanish Workers' Statute requires a monthly summary handed to the person
 * with their payslip.
 */
enum class TipoContrato {
    JORNADA_COMPLETA,
    PARCIAL,
    POR_HORAS
}
