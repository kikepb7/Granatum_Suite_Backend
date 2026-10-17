package com.granatum.core.infrastructure.directory

import com.granatum.core.domain.contract.EmployeeDirectory
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EmployeeStatus
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * This module's side of the [EmployeeDirectory] contract.
 *
 * `timetracking` owns the `employees` table, so it is the only module that can
 * answer. `auth` consumes the interface from `common` and never sees this class
 * - which is the whole point of principle I: the dependency points inward, to
 * the shared contract, and not sideways between features.
 *
 * It lives in `infrastructure/` rather than `service/` because it is an adapter:
 * it translates a persistence detail (`active` as a boolean column) into the
 * vocabulary another feature asked for, and holds no business rule of its own.
 */
@Component
class EmployeeDirectoryJpa(
    private val employeeRepository: EmployeeRepository
) : EmployeeDirectory {

    @Transactional(readOnly = true)
    override fun status(employeeId: EntityId): EmployeeStatus? =
        employeeRepository.findById(employeeId)
            .map { if (it.active) EmployeeStatus.ACTIVE else EmployeeStatus.INACTIVE }
            .orElse(null)

    /**
     * One query, as the contract requires. An empty input short-circuits rather
     * than issuing `IN ()`, which Postgres rejects outright.
     */
    @Transactional(readOnly = true)
    override fun existing(employeeIds: Collection<EntityId>): Set<EntityId> {
        if (employeeIds.isEmpty()) return emptySet()
        return employeeRepository.findExistingIds(employeeIds).toSet()
    }
}
