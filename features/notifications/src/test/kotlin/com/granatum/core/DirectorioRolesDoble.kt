package com.granatum.core

import com.granatum.core.domain.contract.DirectorioRoles
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.Role
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.UUID

/** This module's stand-in for `DirectorioRoles` (feature 008); `auth` implements it in production. */
class DirectorioRolesDoble : DirectorioRoles {

    private val roles = mutableMapOf<EntityId, Role>()

    fun con(rol: Role, id: EntityId = UUID.randomUUID()): EntityId = id.also { roles[it] = rol }

    /** Makes the next lookups fail, to prove a failing notice does not reach the caller. */
    var fallar = false

    fun olvidarTodos() {
        roles.clear()
        fallar = false
    }

    override fun empleadosConRol(roles: Set<Role>): Set<EntityId> {
        check(!fallar) { "fallo provocado por el test" }
        return this.roles.filterValues { it in roles }.keys.toSet()
    }
}

@TestConfiguration
class DirectorioRolesDobleConfig {
    @Bean
    fun directorioRoles() = DirectorioRolesDoble()
}
