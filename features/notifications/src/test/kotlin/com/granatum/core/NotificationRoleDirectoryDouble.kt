package com.granatum.core

import com.granatum.core.domain.contract.RoleDirectory
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.Role
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.UUID

/** This module's stand-in for `RoleDirectory` (feature 008); `auth` implements it in production. */
class NotificationRoleDirectoryDouble : RoleDirectory {

    private val roles = mutableMapOf<EntityId, Role>()

    fun with(role: Role, id: EntityId = UUID.randomUUID()): EntityId = id.also { roles[it] = role }

    /** Makes the next lookups fail, to prove a failing notice does not reach the caller. */
    var fail = false

    fun forgetAll() {
        roles.clear()
        fail = false
    }

    override fun employeesWithRole(roles: Set<Role>): Set<EntityId> {
        check(!fail) { "fallo provocado por el test" }
        return this.roles.filterValues { it in roles }.keys.toSet()
    }
}

@TestConfiguration
class NotificationRoleDirectoryDoubleConfig {
    @Bean
    fun roleDirectory() = NotificationRoleDirectoryDouble()
}
