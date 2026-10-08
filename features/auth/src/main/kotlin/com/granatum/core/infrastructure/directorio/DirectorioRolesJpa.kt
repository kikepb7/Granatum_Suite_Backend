package com.granatum.core.infrastructure.directorio

import com.granatum.core.domain.contract.DirectorioRoles
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.repositories.CuentaAccesoRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** This module's side of the `DirectorioRoles` contract (feature 008). */
@Component
class DirectorioRolesJpa(private val cuentas: CuentaAccesoRepository) : DirectorioRoles {

    @Transactional(readOnly = true)
    override fun empleadosConRol(roles: Set<Role>): Set<EntityId> =
        if (roles.isEmpty()) emptySet() else cuentas.empleadosConRol(roles).toSet()
}
