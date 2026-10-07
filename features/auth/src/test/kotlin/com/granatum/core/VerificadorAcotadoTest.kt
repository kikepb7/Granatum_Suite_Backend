package com.granatum.core

import com.granatum.core.domain.exception.VerificacionSaturadaException
import com.granatum.core.infrastructure.crypto.VerificadorAcotado
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
class VerificadorAcotadoTest {

    private val encoder = mockk<PasswordEncoder>()

    private fun verificador(concurrencia: Int, esperaMs: Long) =
        VerificadorAcotado(encoder, concurrencia, esperaMs)

    @Test
    fun `a verification below the bound just works`() {
        every { encoder.matches(any(), any()) } returns true

        assertTrue(verificador(concurrencia = 2, esperaMs = 50).coincide("x", "hash"))
    }

    /**
     * The whole point: with the single permit taken and the wait elapsed, the
     * second caller is refused rather than queueing forever or allocating a
     * second 64 MiB block.
     */
    @Test
    fun `a second verification beyond the bound is refused once the wait elapses`() {
        val dentro = CountDownLatch(1)
        val suelta = CountDownLatch(1)
        every { encoder.matches(any(), any()) } answers {
            dentro.countDown()
            suelta.await(5, TimeUnit.SECONDS)
            true
        }

        val verificador = verificador(concurrencia = 1, esperaMs = 50)
        val hilos = Executors.newSingleThreadExecutor()
        try {
            hilos.submit { verificador.coincide("x", "hash") }
            assertTrue(dentro.await(5, TimeUnit.SECONDS), "the first call must hold the permit")

            assertFailsWith<VerificacionSaturadaException> {
                verificador.coincide("y", "hash")
            }
        } finally {
            suelta.countDown()
            hilos.shutdown()
            hilos.awaitTermination(5, TimeUnit.SECONDS)
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
        val verificador = verificador(concurrencia = 1, esperaMs = 50)

        repeat(5) {
            assertTrue(
                verificador.coincide("x", "hash"),
                "five sequential calls through a single permit: if it were not released, " +
                    "the second would already have failed"
            )
        }
    }

    @Test
    fun `the permit comes back even when the encoder throws`() {
        every { encoder.matches(any(), any()) } throws IllegalStateException("boom")
        val verificador = verificador(concurrencia = 1, esperaMs = 50)

        assertFailsWith<IllegalStateException> { verificador.coincide("x", "hash") }

        every { encoder.matches(any(), any()) } returns true
        assertTrue(
            verificador.coincide("x", "hash"),
            "a failure inside the encoder must not leak the permit, or one bad hash " +
                "permanently removes capacity until a restart"
        )
    }

    @Test
    fun `encoding is bounded by the same permits as verifying`() {
        every { encoder.encode(any()) } returns "{argon2}\$fake"
        assertEquals("{argon2}\$fake", verificador(concurrencia = 1, esperaMs = 50).codificar("x"))
    }

    /**
     * Saturation must not depend on which account is involved, or it would
     * become a side channel that distinguishes registered addresses from absent
     * ones - exactly what FR-003 closes by hashing a decoy.
     */
    @Test
    fun `saturation does not depend on the password or the stored hash`() {
        val dentro = CountDownLatch(1)
        val suelta = CountDownLatch(1)
        every { encoder.matches(any(), any()) } answers {
            dentro.countDown()
            suelta.await(5, TimeUnit.SECONDS)
            true
        }

        val verificador = verificador(concurrencia = 1, esperaMs = 50)
        val hilos = Executors.newSingleThreadExecutor()
        try {
            hilos.submit { verificador.coincide("real", "hash-real") }
            assertTrue(dentro.await(5, TimeUnit.SECONDS))

            assertFailsWith<VerificacionSaturadaException> {
                verificador.coincide("otra", "hash-senuelo")
            }
        } finally {
            suelta.countDown()
            hilos.shutdown()
            hilos.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
