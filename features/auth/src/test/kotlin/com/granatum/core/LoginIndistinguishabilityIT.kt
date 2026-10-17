package com.granatum.core

import com.granatum.core.domain.exception.InvalidCredentialsException
import com.granatum.core.service.AuthenticationService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * SC-002 and SC-011: an unknown email and a wrong password must be
 * indistinguishable, and signing in must stay under two seconds.
 *
 * ## What this test can and cannot promise
 *
 * The equality is **statistical, not cryptographic**. Promising constant time on
 * the JVM would be a lie - the garbage collector, the JIT and the OS scheduler
 * all contribute noise larger than anything the code does. What matters is that
 * the difference stays far below network noise, so medians over 30 samples are
 * compared rather than single measurements, and the tolerance is generous on
 * purpose.
 *
 * It would be easy to write a stricter version of this test that fails at random
 * in CI and gets deleted within a month. The thing it has to catch is not a
 * 5 ms skew: it is the **absence of the decoy hash**, which makes the unknown
 * path skip Argon2 entirely and answer an order of magnitude faster. That is
 * verified below by comparing the ratio, which is what a missing decoy moves.
 *
 * Argon2 runs with this module's reduced test parameters, so the absolute
 * numbers are small; the relative difference is what is under test.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class LoginIndistinguishabilityIT : BaseAuthIT() {

    companion object {
        /**
         * Production-like cost for this test only. With the module's 1 MiB / t=1
         * the two paths would both be so fast that the decoy's absence could
         * hide inside the noise - which would make the test pass over the very
         * bug it exists to catch.
         */
        @DynamicPropertySource
        @JvmStatic
        fun realCost(registry: DynamicPropertyRegistry) {
            registry.add("auth.password.argon2.memory-kb") { "16384" }
            registry.add("auth.password.argon2.iterations") { "2" }
        }
    }

    @Autowired lateinit var authentication: AuthenticationService

    private val password = "Granatum-2026!"
    private val samples = 30

    private fun measureRejection(email: String, key: String): Long {
        val startedAt = System.nanoTime()
        runCatching { authentication.signIn(email, key) }
            .onSuccess { throw IllegalStateException("se esperaba un rechazo") }
            .onFailure { if (it !is InvalidCredentialsException) throw it }
        return System.nanoTime() - startedAt
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    @Test
    fun `an unknown email and a wrong password take comparable time`() {
        val account = activeAccount(password = password)

        // Warm-up: the first few calls pay JIT compilation, and leaving them in
        // would make whichever path ran first look slower.
        repeat(5) {
            measureRejection(account.email, "incorrecta")
            measureRejection(uniqueEmail(), "incorrecta")
        }

        val withAccount = (1..samples).map { measureRejection(account.email, "incorrecta") }
        val withoutAccount = (1..samples).map { measureRejection(uniqueEmail(), "incorrecta") }

        val medianWithAccount = median(withAccount)
        val medianWithoutAccount = median(withoutAccount)
        val larger = maxOf(medianWithAccount, medianWithoutAccount).toDouble()
        val smaller = minOf(medianWithAccount, medianWithoutAccount).toDouble()

        assertTrue(
            larger / smaller < 2.0,
            "the two paths must cost the same order of magnitude. Without the decoy " +
                "hash the unknown-email path skips Argon2 entirely and answers many " +
                "times faster, which turns sign-in into an oracle for which addresses " +
                "are registered (FR-003, SC-002). " +
                "con cuenta=${medianWithAccount / 1_000_000}ms " +
                "sin cuenta=${medianWithoutAccount / 1_000_000}ms"
        )
    }

    /**
     * A **separate** claim from the one above, not a stronger version of it.
     *
     * Verified by mutation: deleting the decoy verification turns the ratio test
     * red and leaves this one green, because with the test parameters the hash
     * costs about 20 ms and the gap stays under the threshold. So this test does
     * **not** guard the decoy - the ratio test does, and deleting that one would
     * remove the protection even though this one keeps passing.
     *
     * What this one does say is the thing that decides exploitability over a
     * network: a 10x ratio on a 2 ms baseline is unusable to an attacker, while
     * a 2x ratio on 500 ms would not be. Neither assertion subsumes the other.
     */
    @Test
    fun `the difference between the two medians is small in absolute terms`() {
        val account = activeAccount(password = password)
        repeat(5) { measureRejection(account.email, "incorrecta") }

        val withAccount = median((1..samples).map { measureRejection(account.email, "incorrecta") })
        val withoutAccount = median((1..samples).map { measureRejection(uniqueEmail(), "incorrecta") })

        val differenceMs = abs(withAccount - withoutAccount) / 1_000_000
        assertTrue(
            differenceMs < 50,
            "a gap this size is well inside network jitter, so it cannot be used to " +
                "distinguish the two cases over the wire: ${differenceMs}ms"
        )
    }

    /** SC-011, asserted on both the successful and the rejected path. */
    @Test
    fun `signing in finishes in under two seconds`() {
        val account = activeAccount(password = password)

        val successStart = System.nanoTime()
        authentication.signIn(account.email, password)
        val successMs = (System.nanoTime() - successStart) / 1_000_000

        val rejectionMs = measureRejection(account.email, "incorrecta") / 1_000_000

        assertTrue(successMs < 2_000, "a successful sign-in took ${successMs}ms")
        assertTrue(rejectionMs < 2_000, "a rejected sign-in took ${rejectionMs}ms")
    }
}
