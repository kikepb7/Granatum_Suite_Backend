package com.granatum.core.service

import com.granatum.core.domain.contract.EmployeeDirectory
import com.granatum.core.infrastructure.database.repositories.AccountRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Finds access accounts whose person no longer exists (FR-029c).
 *
 * ## Why this has to exist
 *
 * The database cannot prevent these rows. `accounts.employee_id` has no
 * foreign key to `empleados`, because that table belongs to `timetracking` and
 * constitution principle I forbids this module from depending on it. Creating an
 * account for nobody is refused up front, but a person can stop existing *after*
 * their account was created, and nothing in the schema would notice. So FR-029c
 * asks for orphans to be **detected**, not only prevented - this is the
 * detection.
 *
 * An orphan cannot sign in either way - `AuthenticationService` refuses any
 * account whose person the directory does not report as active - so this is
 * about keeping the table honest, not about closing a hole.
 *
 * ## Why in batches
 *
 * One `EmployeeDirectory.existing(...)` call per page of accounts, never one
 * `status(...)` per account: a loop over every row would be a guaranteed N+1
 * across a module boundary, invisible from this side of the interface.
 */
@Service
class OrphanDetectionService(
    private val accounts: AccountRepository,
    private val directory: EmployeeDirectory
) {

    data class OrphanAccount(
        val accountId: UUID,
        val employeeId: UUID,
        val createdAt: Instant
    )

    @Transactional(readOnly = true)
    fun findOrphans(): List<OrphanAccount> {
        val orphans = mutableListOf<OrphanAccount>()
        var page = accounts.findAllByOrderByIdAsc(PageRequest.of(0, BATCH_SIZE))

        while (true) {
            val batch = page.content
            val existing = directory.existing(batch.map { it.employeeId })

            batch.filter { it.employeeId !in existing }
                // Deliberately without the email: identifying the row is enough
                // to clean it up, and an address would be personal data in a
                // diagnostic listing that has no use for it.
                .mapTo(orphans) { OrphanAccount(it.id, it.employeeId, it.createdAt) }

            if (!page.hasNext()) break
            page = accounts.findAllByOrderByIdAsc(page.nextPageable())
        }
        return orphans
    }

    companion object {
        const val BATCH_SIZE = 500
    }
}
