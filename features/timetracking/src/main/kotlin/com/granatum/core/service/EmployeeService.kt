package com.granatum.core.service

import com.granatum.core.api.validation.IdentityDocumentValidator
import com.granatum.core.domain.exception.DuplicateDocumentException
import com.granatum.core.domain.exception.EmployeeNotFoundException
import com.granatum.core.domain.model.EmployeeModel
import com.granatum.core.domain.type.ContractType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * Staff management. ADMIN only, enforced at the route in `SecurityConfig`
 * (FR-027): an MANAGER runs inventory and approves corrections but does not
 * manage people.
 *
 * **There is no delete.** Removing a person would destroy the working-time
 * history that has to be kept for four years, so deactivation is the only exit
 * (FR-030). The repository does not expose one either, so this is not a matter
 * of discipline.
 */
@Service
class EmployeeService(
    private val employeeRepository: EmployeeRepository
) {

    @Transactional
    fun create(
        name: String,
        lastName: String,
        identityDocument: String,
        position: String,
        contractType: ContractType,
        hireDate: LocalDate
    ): EmployeeModel {
        // Normalised before the uniqueness check, or FR-029 could be bypassed
        // with a hyphen: `12345678z` and `12345678-Z` are the same person.
        val document = IdentityDocumentValidator.normalize(identityDocument)

        if (employeeRepository.existsByIdentityDocument(document)) {
            // The message names the field, never the value: it reaches clients
            // and logs, and the document is personal data (principle VI).
            throw DuplicateDocumentException()
        }

        return employeeRepository.save(
            EmployeeEntity(
                name = name,
                lastName = lastName,
                identityDocument = document,
                position = position,
                contractType = contractType,
                hireDate = hireDate,
                active = true
            )
        ).toModel()
    }

    /** `identityDocument` and `active` are not touched here; see the request DTO. */
    @Transactional
    fun update(
        id: UUID,
        name: String,
        lastName: String,
        position: String,
        contractType: ContractType,
        hireDate: LocalDate
    ): EmployeeModel {
        val employee = employeeRepository.findById(id)
            .orElseThrow { EmployeeNotFoundException(id) }

        employee.name = name
        employee.lastName = lastName
        employee.position = position
        employee.contractType = contractType
        employee.hireDate = hireDate

        return employeeRepository.save(employee).toModel()
    }

    /**
     * Engagement or exit (FR-030).
     *
     * Deactivating stops the person clocking in and leaves their history whole.
     * An open shift is deliberately not blocked here: the daily job will mark it
     * INCOMPLETE, and holding up an administrative action because someone forgot
     * to clock out would make one person's mistake somebody else's problem.
     */
    @Transactional
    fun setActive(id: UUID, active: Boolean): EmployeeModel {
        val employee = employeeRepository.findById(id)
            .orElseThrow { EmployeeNotFoundException(id) }

        employee.active = active

        return employeeRepository.save(employee).toModel()
    }

    @Transactional(readOnly = true)
    fun findById(id: UUID): EmployeeModel =
        employeeRepository.findById(id)
            .orElseThrow { EmployeeNotFoundException(id) }
            .toModel()

    @Transactional(readOnly = true)
    fun findAll(active: Boolean?): List<EmployeeModel> =
        when (active) {
            null -> employeeRepository.findAll()
            else -> employeeRepository.findAllByActive(active)
        }.map { it.toModel() }
}
