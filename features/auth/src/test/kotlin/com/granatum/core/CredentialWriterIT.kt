package com.granatum.core

import com.granatum.core.service.CredentialWriter
import com.granatum.core.service.AccountLockoutManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals

/**
 * The lost update that `CredentialWriter` exists to prevent.
 *
 * Changing or resetting a password hashes first - ~110 ms, outside any
 * transaction - and writes afterwards. The first version wrote by calling
 * `save` on the entity loaded **before** the hash, which by then is detached:
 * `save` merges every column from that stale copy, so a failed sign-in that
 * bumped the lockout counter during the hash was silently reverted.
 *
 * Reproduced deterministically rather than with threads: load the account,
 * record a failure the way a concurrent sign-in would, then write the new
 * credential. The old code reverts the counter to its loaded value; the
 * current one re-reads the row under lock and leaves it alone.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class CredentialWriterIT : BaseAuthIT() {

    @Autowired lateinit var writer: CredentialWriter
    @Autowired lateinit var lockoutManager: AccountLockoutManager

    @Test
    fun `writing a new credential does not revert a failure counted in the meantime`() {
        val account = activeAccount()
        val loadedBefore = accounts.findById(account.id).orElseThrow()
        assertEquals(0, loadedBefore.lockoutState.failedAttempts.toInt())

        // What a concurrent failed sign-in does while the change is hashing.
        lockoutManager.recordFailure(account.id)
        lockoutManager.recordFailure(account.id)

        writer.write(
            accountId = loadedBefore.id,
            hash = verifier.encode("Granatum-2027!"),
            changeRequired = false,
            liftLockout = false
        )

        assertEquals(
            2,
            accounts.findById(account.id).orElseThrow().lockoutState.failedAttempts.toInt(),
            "a password change must not wipe failures counted while it was hashing - " +
                "that would hand an attacker free guesses by timing them around a change"
        )
    }

    @Test
    fun `a reset does clear the counter, because that is what it is for`() {
        val account = activeAccount()
        lockoutManager.recordFailure(account.id)
        lockoutManager.recordFailure(account.id)

        writer.write(
            accountId = account.id,
            hash = verifier.encode("Granatum-2027!"),
            changeRequired = true,
            liftLockout = true
        )

        assertEquals(0, accounts.findById(account.id).orElseThrow().lockoutState.failedAttempts.toInt())
    }
}
