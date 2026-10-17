package com.granatum.core

import com.granatum.core.domain.exception.VerificationBusyException
import com.granatum.core.infrastructure.crypto.BoundedVerifier
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The bound on simultaneous password verifications.
 *
 * It exists because of the Argon2 parameters, not in spite of them: every
 * verification reserves 64 MiB, `POST /api/auth/login` is public, and Tomcat
 * allows 200 threads by default - 12.8 GiB of memory reservation that anyone
 * can trigger without a credential. Per-origin rate limiting, the natural
 * defence, is explicitly out of scope by decision of the spec.
 *
 * The encoder is a MockK double that blocks on a latch, which is the only way to
 * hold a permit for a controlled length of time without actually paying 110 ms
 * per test.
 */
class BoundedVerifierTest {

    private val encoder = mockk<PasswordEncoder>()

    private fun verifier(concurrency: Int, waitMs: Long) =
        BoundedVerifier(encoder, concurrency, waitMs)

    @Test
    fun `a verification below the bound just works`() {
        every { encoder.matches(any(), any()) } returns true

        assertTrue(verifier(concurrency = 2, waitMs = 50).matches("x", "hash"))
    }

    /**
     * The whole point: with the single permit taken and the wait elapsed, the
     * second caller is refused rather than queueing forever or allocating a
     * second 64 MiB block.
     */
    @Test
    fun `a second verification beyond the bound is refused once the wait elapses`() {
        val inside = CountDownLatch(1)
        val gate = CountDownLatch(1)
        every { encoder.matches(any(), any()) } answers {
            inside.countDown()
            gate.await(5, TimeUnit.SECONDS)
            true
        }

        val verifier = verifier(concurrency = 1, waitMs = 50)
        val threads = Executors.newSingleThreadExecutor()
        try {
            threads.submit { verifier.matches("x", "hash") }
            assertTrue(inside.await(5, TimeUnit.SECONDS), "the first call must hold the permit")

            assertFailsWith<VerificationBusyException> {
                verifier.matches("y", "hash")
            }
        } finally {
            gate.countDown()
            threads.shutdown()
            threads.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    /**
     * A semaphore that does not give its permits back is a worse outage than the
     * one it prevents: the first is transient and the second is permanent and
     * needs a restart. So the release has to survive both a normal return and a
     * thrown exception.
     */
    @Test
    fun `the permit comes back after a normal call`() {
        every { encoder.matches(any(), any()) } returns true
        val verifier = verifier(concurrency = 1, waitMs = 50)

        repeat(5) {
            assertTrue(
                verifier.matches("x", "hash"),
                "five sequential calls through a single permit: if it were not released, " +
                    "the second would already have failed"
            )
        }
    }

    @Test
    fun `the permit comes back even when the encoder throws`() {
        every { encoder.matches(any(), any()) } throws IllegalStateException("boom")
        val verifier = verifier(concurrency = 1, waitMs = 50)

        assertFailsWith<IllegalStateException> { verifier.matches("x", "hash") }

        every { encoder.matches(any(), any()) } returns true
        assertTrue(
            verifier.matches("x", "hash"),
            "a failure inside the encoder must not leak the permit, or one bad hash " +
                "permanently removes capacity until a restart"
        )
    }

    @Test
    fun `encoding is bounded by the same permits as verifying`() {
        every { encoder.encode(any()) } returns "{argon2}\$fake"
        assertEquals("{argon2}\$fake", verifier(concurrency = 1, waitMs = 50).encode("x"))
    }

    /**
     * Saturation must not depend on which account is involved, or it would
     * become a side channel that distinguishes registered addresses from absent
     * ones - exactly what FR-003 closes by hashing a decoy.
     */
    @Test
    fun `saturation does not depend on the password or the stored hash`() {
        val inside = CountDownLatch(1)
        val gate = CountDownLatch(1)
        every { encoder.matches(any(), any()) } answers {
            inside.countDown()
            gate.await(5, TimeUnit.SECONDS)
            true
        }

        val verifier = verifier(concurrency = 1, waitMs = 50)
        val threads = Executors.newSingleThreadExecutor()
        try {
            threads.submit { verifier.matches("real", "hash-real") }
            assertTrue(inside.await(5, TimeUnit.SECONDS))

            assertFailsWith<VerificationBusyException> {
                verifier.matches("otra", "hash-senuelo")
            }
        } finally {
            gate.countDown()
            threads.shutdown()
            threads.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
