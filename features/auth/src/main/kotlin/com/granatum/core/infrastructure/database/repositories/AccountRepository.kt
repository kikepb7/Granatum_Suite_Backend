package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.AccountEntity
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.repository.Repository
import java.util.Optional
import java.util.UUID

/**
 * Deliberately extends [Repository] and not `JpaRepository`.
 *
 * There is no route in this application that deletes an access account:
 * revoking someone's access is done by deactivating the person in `empleados`,
 * which is where that fact lives. Constitution principle III requires the
 * repository of a table that admits no deletion not to expose the operation at
 * all - not calling `delete` is not enough, because an interface that offers it
 * will eventually be used by accident, and declared debt nº 2 of the
 * constitution is the standing example of what that looks like.
 */
interface AccountRepository : Repository<AccountEntity, UUID> {

    fun save(account: AccountEntity): AccountEntity

    fun findById(id: UUID): Optional<AccountEntity>

    /** The email must already be normalised; see `EmailNormalizer`. */
    fun findByEmail(email: String): AccountEntity?

    fun existsByEmail(email: String): Boolean

    fun findByEmployeeId(employeeId: UUID): AccountEntity?

    fun existsByEmployeeId(employeeId: UUID): Boolean

    /** Only for the orphan-account sweep, which has to look at every row. */
    fun findAll(): List<AccountEntity>

    /**
     * Whether any account holds this role. Feature 005 asks it of `ADMIN`: the
     * bootstrap code only works while the answer is no (FR-012).
     */
    fun existsByRole(role: com.granatum.core.domain.type.Role): Boolean

    /** Feature 008: the staff ids holding any of these roles, for RoleDirectory. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT c.employeeId FROM AccountEntity c WHERE c.role IN :roles"
    )
    fun employeesWithRole(
        @org.springframework.data.repository.query.Param("roles") roles: Collection<com.granatum.core.domain.type.Role>
    ): List<UUID>

    /**
     * The orphan sweep, a page at a time.
     *
     * A `Slice` rather than a `Page`: the sweep only needs to know whether there
     * is a next page, and a `Page` would issue a `count(*)` per page for a total
     * nobody reads. Ordered by id so the pages are stable while the sweep walks
     * them.
     */
    fun findAllByOrderByIdAsc(pageable: Pageable): Slice<AccountEntity>

    /**
     * The same row, under a write lock, for applying a lockout transition.
     *
     * Two simultaneous failed attempts both reading `failed_attempts = 4` and
     * both writing `5` would leave the account unlocked, so the read that
     * decides the transition has to be serialised. The lock is per account and
     * is held for microseconds.
     *
     * **The password verification must happen before this, outside the
     * transaction.** Holding a row lock across the ~110 ms of Argon2 would
     * serialise every attempt against one account and turn the hardening into
     * the amplifier of a denial of service - precisely what FR-016c exists to
     * prevent by another route.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: UUID): Optional<AccountEntity>
}
