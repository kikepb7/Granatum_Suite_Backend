package com.granatum.core

import com.granatum.core.domain.contract.NewStaffRecord
import com.granatum.core.domain.contract.EmployeeDirectory
import com.granatum.core.domain.contract.StaffRecords
import com.granatum.core.validation.NifValidator
import java.util.UUID
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EmployeeStatus
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * The `auth` module's stand-in for `EmployeeDirectory`.
 *
 * `timetracking` owns the real implementation and this module does not depend on
 * it - that is the whole point of the contract (principle I). So a double is not
 * a shortcut here: it is the only thing that *can* exist on this classpath, and
 * the real wiring is exercised in `app`, which has both modules.
 *
 * Mutable on purpose: a test needs to dismiss somebody between two calls to
 * check that renewal is refused while an already-issued access token is not
 * (FR-010).
 */
class AuthEmployeeDirectoryFake : EmployeeDirectory, StaffRecords {

    private val statuses = mutableMapOf<EntityId, EmployeeStatus>()

    fun registerActive(id: EntityId) { statuses[id] = EmployeeStatus.ACTIVE }

    fun registerInactive(id: EntityId) { statuses[id] = EmployeeStatus.INACTIVE }

    fun forget(id: EntityId) { statuses.remove(id) }

    override fun status(employeeId: EntityId): EmployeeStatus? = statuses[employeeId]

    override fun existing(employeeIds: Collection<EntityId>): Set<EntityId> =
        employeeIds.filter { statuses.containsKey(it) }.toSet()

    // Feature 005: the StaffRecords side. A staff record created here is also
    // registered as an active person, as the real implementation's would be.
    private val byDocument = mutableMapOf<String, EntityId>()
    val createdRecords = mutableListOf<NewStaffRecord>()

    fun registerStaffRecord(identityDocument: String, id: EntityId = UUID.randomUUID()): EntityId {
        byDocument[NifValidator.normalize(identityDocument)] = id
        registerActive(id)
        return id
    }

    override fun findByDocument(identityDocument: String): EntityId? = byDocument[NifValidator.normalize(identityDocument)]

    override fun create(record: NewStaffRecord): EntityId {
        require(record.contractType in setOf("FULL_TIME", "PART_TIME", "HOURLY"))
        createdRecords += record
        return registerStaffRecord(record.identityDocument)
    }
}

@TestConfiguration
class AuthEmployeeDirectoryFakeConfig {
    // One instance behind both contracts, so a staff record created through
    // StaffRecords is visible to EmployeeDirectory - as in production, where
    // both read the same table.
    @Bean
    fun employeeDirectory() = AuthEmployeeDirectoryFake()
}
