package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.util.Optional
import java.util.UUID

/**
 * Deliberately extends [Repository] and not `JpaRepository`.
 *
 * FR-030 forbids deleting staff, and constitution principle III (v2.0.0)
 * requires repositories of append-only or non-deletable tables not to expose
 * mutation operations at all. Not calling `delete` is not enough: an interface
 * that offers it will eventually be used by accident, and there would be
 * nothing to stop it.
 */
interface EmployeeRepository : Repository<EmployeeEntity, UUID> {
    fun save(employee: EmployeeEntity): EmployeeEntity
    fun findById(id: UUID): Optional<EmployeeEntity>
    fun findAll(): List<EmployeeEntity>
    fun findAllByActive(active: Boolean): List<EmployeeEntity>

    /** Several people in one query: an export resolves who requested and who approved each correction of a batch this way. */
    fun findAllByIdIn(ids: Collection<UUID>): List<EmployeeEntity>
    fun findByIdentityDocument(identityDocument: String): EmployeeEntity?
    fun existsByIdentityDocument(identityDocument: String): Boolean

    /**
     * Only the ids, and only those that exist. Serves
     * [com.granatum.core.domain.contract.EmployeeDirectory.existing], whose
     * contract requires a single query: the orphan-account sweep walks every
     * account, so a loop would be a guaranteed N+1.
     *
     * Returns the ids rather than the entities on purpose. The caller needs to
     * know which exist, not who they are, and loading full staff records -
     * names and identity documents included - to answer that would be handing
     * personal data to a caller that has no use for it (principle VI).
     */
    @Query("SELECT e.id FROM EmployeeEntity e WHERE e.id IN :ids")
    fun findExistingIds(@Param("ids") ids: Collection<UUID>): List<UUID>
}
