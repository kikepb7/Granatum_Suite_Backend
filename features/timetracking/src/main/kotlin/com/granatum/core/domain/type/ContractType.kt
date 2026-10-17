package com.granatum.core.domain.type

/**
 * Contract type. [PART_TIME] carries an extra legal obligation: article 12.4.c of
 * the Spanish Workers' Statute requires a monthly summary handed to the person
 * with their payslip.
 */
enum class ContractType {
    FULL_TIME,
    PART_TIME,
    HOURLY
}
