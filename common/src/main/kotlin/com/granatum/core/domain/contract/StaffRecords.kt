package com.granatum.core.domain.contract

import com.granatum.core.domain.type.EntityId
import java.time.LocalDate

/**
 * What `auth` needs to turn a sign-up into a person on the staff register
 * (feature 005): find the record that already carries an identity document, or
 * create one.
 *
 * ## Why a second contract and not more methods on [EmployeeDirectory]
 *
 * That contract was designed to expose *no* personal data - only whether a
 * person exists and is employed - and says so. This one necessarily handles a
 * name and an identity document, and it writes. Folding the two together would
 * hand every consumer of the narrow read contract a way to create staff records,
 * which is exactly the kind of widening principle I asks to keep visible. Two
 * contracts, each with one purpose, can each be audited at a glance.
 *
 * `timetracking` implements it; `auth` compiles against it and never mentions
 * `timetracking` (constitution principle I).
 *
 * ## Transactions
 *
 * Implementations MUST join the caller's transaction (default propagation):
 * approving a sign-up creates the staff record and the account together, or
 * neither (specs/005-staff-registration research.md D-005).
 */
interface StaffRecords {

    /**
     * The id of the staff record with this identity document, or `null`.
     * The document is normalised by the implementation, so `12345678-z` finds
     * `12345678Z`.
     */
    fun findByDocument(identityDocument: String): EntityId?

    /** Creates an active staff record and returns its id. */
    fun create(record: NewStaffRecord): EntityId
}

/**
 * The data a new staff record needs.
 *
 * `contractType` travels as text because the enum belongs to `timetracking`;
 * it must be one of `FULL_TIME`, `PART_TIME` or `HOURLY`, which the
 * caller validates at its edge and the implementation enforces again.
 *
 * `toString` is overridden: name, last name and document are personal data and a data
 * class would print them in any log line or exception message that mentions
 * the object (principle VI).
 */
data class NewStaffRecord(
    val name: String,
    /** Required since feature 012: every new staff record has one (FR-001). */
    val lastName: String,
    val identityDocument: String,
    val position: String,
    val contractType: String,
    val hireDate: LocalDate
) {
    override fun toString(): String =
        "NewStaffRecord(position=$position, contractType=$contractType, hireDate=$hireDate)"
}
