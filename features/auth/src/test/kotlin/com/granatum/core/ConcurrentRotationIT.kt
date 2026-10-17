package com.granatum.core

import com.granatum.core.domain.exception.InvalidRefreshTokenException
import com.granatum.core.infrastructure.database.repositories.RefreshSessionRepository
import com.granatum.core.service.AuthenticationService
import com.granatum.core.service.RefreshTokenGenerator
import com.granatum.core.service.SessionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SC-004 asks for 100%, and 100% is only provable under concurrency.
 *
 * ## What this test is for
 *
 * It is the one that distinguishes a conditional `UPDATE` from a
 * read-check-write. Sequentially, both implementations pass every other test in
 * this module: the second renewal finds the token used and refuses it. Only
 * simultaneous callers reveal the difference, because read-check-write lets two
 * of them pass the check before either writes, and both get a valid token pair
 * from a token that was supposed to work once.
 *
 * This is the exact hole task T060 of feature 001 had to be rewritten to close,
 * and it was found by review rather than by a test. This is that test.
 *
 * Threads rather than a mocked race: the guarantee lives in the database's
 * `WHERE` clause, so anything that does not go through a real Postgres proves
 * nothing about it.
 *
 * ## Verified by mutation
 *
 * Replacing the conditional `UPDATE` with a read-check-write makes **both**
 * assertions below fail - eight winners and eight live sessions from one
 * single-use token - while every test in `RefreshIT` stays green. That is
 * the measure of what this file is worth: without it, the suite would be fully
 * green over an implementation that issues eight valid token pairs from one
 * token.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class ConcurrentRotationIT : BaseAuthIT() {

    @Autowired lateinit var authentication: AuthenticationService
    @Autowired lateinit var sessionService: SessionService
    @Autowired lateinit var sessions: RefreshSessionRepository
    @Autowired lateinit var tokenGenerator: RefreshTokenGenerator

    private val password = "Granatum-2026!"
    private val threads = 8

    @Test
    fun `eight simultaneous renewals with the same token produce exactly one winner`() {
        val account = activeAccount(password = password)
        val pair = authentication.signIn(account.email, password)

        val winners = AtomicInteger(0)
        val rejections = AtomicInteger(0)
        val unexpected = mutableListOf<Throwable>()

        // All threads block on the same latch so they hit the UPDATE together.
        // Launching them in a loop would serialise them by accident and the test
        // would pass over a read-check-write implementation.
        val output = CountDownLatch(1)
        val ready = CountDownLatch(threads)
        val finished = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            pool.submit {
                ready.countDown()
                output.await(10, TimeUnit.SECONDS)
                try {
                    sessionService.rotate(pair.refreshToken)
                    winners.incrementAndGet()
                } catch (e: InvalidRefreshTokenException) {
                    rejections.incrementAndGet()
                } catch (e: Throwable) {
                    synchronized(unexpected) { unexpected += e }
                } finally {
                    finished.countDown()
                }
            }
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "every thread must be ready first")
        output.countDown()
        assertTrue(finished.await(30, TimeUnit.SECONDS), "every thread must finish")
        pool.shutdown()

        assertEquals(
            emptyList(),
            unexpected.map { "${it::class.simpleName}: ${it.message}" },
            "only a clean rejection is acceptable for the losers"
        )
        assertEquals(
            1,
            winners.get(),
            "exactly one thread may rotate the token. More than one means the single-use " +
                "guarantee is a check rather than a constraint, and SC-004 asks for 100%"
        )
        assertEquals(threads - 1, rejections.get())
    }

    /**
     * The other half of the guarantee: one winner must also mean **one** new
     * session. A race that issued one token pair but inserted several rows
     * would leave extra live sessions nobody can see.
     */
    @Test
    fun `one winner means one live session afterwards`() {
        val account = activeAccount(password = password)
        val pair = authentication.signIn(account.email, password)

        val output = CountDownLatch(1)
        val finished = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.submit {
                output.await(10, TimeUnit.SECONDS)
                runCatching { sessionService.rotate(pair.refreshToken) }
                finished.countDown()
            }
        }
        output.countDown()
        assertTrue(finished.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(
            1,
            sessions.countByAccountIdAndUsedAtIsNullAndRevokedAtIsNull(account.id),
            "the consumed token plus exactly one replacement"
        )
        assertEquals(
            java.time.Instant::class,
            sessions.findByTokenHash(tokenGenerator.hash(pair.refreshToken))!!.usedAt!!::class,
            "the presented token must be marked used exactly once"
        )
    }
}
