package com.granatum.core.infrastructure.directory

import com.granatum.core.domain.contract.RoleDirectory
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.repositories.AccountRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** This module's side of the `RoleDirectory` contract (feature 008). */
@Component
class JpaRoleDirectory(private val accounts: AccountRepository) : RoleDirectory {

    @Transactional(readOnly = true)
    override fun employeesWithRole(roles: Set<Role>): Set<EntityId> =
        if (roles.isEmpty()) emptySet() else accounts.employeesWithRole(roles).toSet()
}
