package com.granatum.core

import com.granatum.core.service.OrphanDetectionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-029c: accounts whose person no longer exists are detected.
 *
 * Uses the module's `EmployeeDirectory` double, which is what lets a test
 * make a person disappear after their account exists - precisely the situation
 * the database cannot prevent, because there is no foreign key across modules.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class OrphanAccountsIT : BaseAuthIT() {

    @Autowired lateinit var detection: OrphanDetectionService

    @Test
    fun `an account whose person disappeared is listed and a normal one is not`() {
        val orphan = activeAccount()
        val normal = activeAccount()
        directory.forget(orphan.employeeId)

        val ids = detection.findOrphans().map { it.accountId }

        assertTrue(orphan.id in ids, "the orphan must surface")
        assertFalse(normal.id in ids, "a healthy account must not")
    }

    /**
     * An inactive person still *exists*. Confusing the two would list everyone
     * on leave as an orphan, and the obvious "fix" - deleting their
     * credentials - would cut them off from a working-time record the law says
     * they are entitled to see.
     */
    @Test
    fun `an account whose person is merely inactive is not an orphan`() {
        val account = activeAccount()
        directory.registerInactive(account.employeeId)

        assertFalse(account.id in detection.findOrphans().map { it.accountId })
    }

    /** The listing carries ids, and nothing that identifies the person further. */
    @Test
    fun `the listing does not carry the email`() {
        val orphan = activeAccount()
        directory.forget(orphan.employeeId)

        val row = detection.findOrphans().single { it.accountId == orphan.id }

        assertFalse(row.toString().contains(orphan.email))
    }

    /**
     * More accounts than one batch, so the paging is exercised and an orphan on
     * the second page is not silently skipped.
     */
    @Test
    fun `orphans beyond the first batch are found too`() {
        val many = (1..OrphanDetectionService.BATCH_SIZE + 5).map { activeAccount() }
        val latest = many.maxBy { it.id }
        directory.forget(latest.employeeId)

        assertTrue(
            latest.id in detection.findOrphans().map { it.accountId },
            "the account with the highest id lands on the last page: if it is missing, " +
                "the sweep stops after the first batch"
        )
    }
}
