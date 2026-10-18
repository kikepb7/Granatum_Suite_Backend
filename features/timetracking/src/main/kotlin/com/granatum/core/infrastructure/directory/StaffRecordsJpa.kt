package com.granatum.core.infrastructure.directory

import com.granatum.core.api.validation.IdentityDocumentValidator
import com.granatum.core.domain.contract.NewStaffRecord
import com.granatum.core.domain.contract.StaffRecords
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.ContractType
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.service.EmployeeService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * This module's side of the `StaffRecords` contract (feature 005).
 *
 * Creation goes through [EmployeeService.create], the same path as the manual
 * staff registration of feature 001, so the document is normalised and its
 * uniqueness checked in exactly one place.
 *
 * Default propagation on purpose: the contract requires joining the caller's
 * transaction, so a failure creating the account in `auth` rolls this record
 * back too.
 */
@Component
class StaffRecordsJpa(
    private val employeeRepository: EmployeeRepository,
    private val employeeService: EmployeeService
) : StaffRecords {

    @Transactional(readOnly = true)
    override fun findByDocument(document: String): EntityId? =
        employeeRepository.findByIdentityDocument(IdentityDocumentValidator.normalize(document))?.id

    @Transactional
    override fun create(newRecord: NewStaffRecord): EntityId =
        employeeService.create(
            name = newRecord.name,
            lastName = newRecord.lastName,
            identityDocument = newRecord.identityDocument,
            position = newRecord.position,
            // valueOf, not a lenient parse: an unknown contract type is a bug in
            // the caller, which validates it at its edge, and must not become a
            // silently defaulted staff record.
            contractType = ContractType.valueOf(newRecord.contractType),
            hireDate = newRecord.hireDate
        ).id
}
