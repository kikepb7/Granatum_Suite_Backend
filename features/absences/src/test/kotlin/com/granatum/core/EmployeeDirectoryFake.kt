package com.granatum.core

import com.granatum.core.domain.contract.EmployeeDirectory
import com.granatum.core.domain.type.EmployeeStatus
import com.granatum.core.domain.type.EntityId
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.UUID

/**
 * This module's stand-in for `EmployeeDirectory` (feature 007). In
 * production `timetracking` implements it; here the staff register is a map,
 * because this module must not depend on `timetracking` even in tests.
 */
class EmployeeDirectoryFake : EmployeeDirectory {

    private val statuses = mutableMapOf<EntityId, EmployeeStatus>()

    fun active(id: EntityId = UUID.randomUUID()): EntityId = id.also { statuses[it] = EmployeeStatus.ACTIVE }

    fun inactive(id: EntityId = UUID.randomUUID()): EntityId = id.also { statuses[it] = EmployeeStatus.INACTIVE }

    override fun status(employeeId: EntityId): EmployeeStatus? = statuses[employeeId]

    override fun existing(employeeIds: Collection<EntityId>): Set<EntityId> =
        employeeIds.filter { statuses.containsKey(it) }.toSet()
}

@TestConfiguration
class EmployeeDirectoryFakeConfig {
    @Bean
    fun employeeDirectory() = EmployeeDirectoryFake()
}
