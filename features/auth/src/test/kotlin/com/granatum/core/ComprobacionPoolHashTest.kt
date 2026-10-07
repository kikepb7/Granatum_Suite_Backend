package com.granatum.core

import com.granatum.core.infrastructure.crypto.ComprobacionPoolHash
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The boot check that stops a configuration which only fails under load.
 *
 * A pure unit test: the rule is arithmetic, and what matters is that the
 * application refuses to start rather than that Spring wires it.
 */
class ComprobacionPoolHashTest {

    @Test
    fun `a pool of exactly twice the concurrency is accepted`() {
        ComprobacionPoolHash(concurrencia = 4, tamanoPool = 8).comprobar()
    }

    @Test
    fun `a larger pool is accepted`() {
        ComprobacionPoolHash(concurrencia = 4, tamanoPool = 10).comprobar()
    }

    /**
     * The production default before the invariant was found: 16 permits against
     * a pool of 10, which is what hung the test suite.
     */
    @Test
    fun `the combination that deadlocked is refused`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ComprobacionPoolHash(concurrencia = 16, tamanoPool = 10).comprobar()
        }

        assertTrue(
            error.message!!.contains("DB_POOL_MAX_SIZE") &&
                error.message!!.contains("AUTH_HASH_CONCURRENCIA"),
            "the message has to name both environment variables and both ways out, " +
                "because whoever hits it is looking at a startup failure and not at " +
                "this code: ${error.message}"
        )
    }

    @Test
    fun `one short of the minimum is still refused`() {
        assertFailsWith<IllegalArgumentException> {
            ComprobacionPoolHash(concurrencia = 4, tamanoPool = 7).comprobar()
        }
    }
}
