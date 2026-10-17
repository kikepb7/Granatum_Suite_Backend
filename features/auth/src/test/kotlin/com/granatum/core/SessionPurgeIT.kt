package com.granatum.core

import com.granatum.core.infrastructure.database.entities.AccountEntity
import com.granatum.core.infrastructure.database.entities.RefreshSessionEntity
import com.granatum.core.infrastructure.database.repositories.RefreshSessionRepository
import com.granatum.core.scheduling.SessionPurgeJob
import com.granatum.core.service.RefreshTokenGenerator
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The session purge (D-015, an addition of the plan).
 *
 * The boundary that matters is the one between dead and alive: a purge that
 * took a live session would log somebody out at 04:45 for no reason, which in
 * this product means a person who cannot clock in when they arrive.
 *
 * Rows are inserted with the dates the test needs, because what is under test
 * is the bound in the query, not the passage of time.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class SessionPurgeIT : BaseAuthIT() {

    @Autowired lateinit var job: SessionPurgeJob
    @Autowired lateinit var sessions: RefreshSessionRepository
    @Autowired lateinit var tokenGenerator: RefreshTokenGenerator

    private val now: Instant = Instant.now()

    private fun session(
        account: AccountEntity,
        expiresAt: Instant = now.plus(30, ChronoUnit.DAYS),
        usedAt: Instant? = null,
        revokedAt: Instant? = null
    ): String {
        val hash = tokenGenerator.hash(tokenGenerator.generate())
        sessions.save(
            RefreshSessionEntity(
                account = account,
                tokenHash = hash,
                expiresAt = expiresAt,
                createdAt = now.minus(400, ChronoUnit.DAYS),
                usedAt = usedAt,
                revokedAt = revokedAt,
                revocationReason = if (revokedAt != null) "LOGOUT" else null
            )
        )
        return hash
    }

    private fun ago(days: Long) = now.minus(days, ChronoUnit.DAYS)

    @Test
    fun `a session used 31 days ago is purged`() {
        val hash = session(activeAccount(), usedAt = ago(31))
        job.purge()
        assertNull(sessions.findByTokenHash(hash))
    }

    @Test
    fun `a session revoked 31 days ago is purged`() {
        val hash = session(activeAccount(), revokedAt = ago(31))
        job.purge()
        assertNull(sessions.findByTokenHash(hash))
    }

    @Test
    fun `a session expired 31 days ago is purged`() {
        val hash = session(activeAccount(), expiresAt = ago(31))
        job.purge()
        assertNull(sessions.findByTokenHash(hash))
    }

    /** Inside the 30 days the row stays: it may still be wanted in an investigation. */
    @Test
    fun `a session dead for only 29 days survives`() {
        val used = session(activeAccount(), usedAt = ago(29))
        val revoked = session(activeAccount(), revokedAt = ago(29))
        val expired = session(activeAccount(), expiresAt = ago(29))

        job.purge()

        assertNotNull(sessions.findByTokenHash(used))
        assertNotNull(sessions.findByTokenHash(revoked))
        assertNotNull(sessions.findByTokenHash(expired))
    }

    /**
     * The one that matters most. Created 400 days ago, still unused, unrevoked
     * and unexpired: it is a session somebody is using, and age alone must
     * never make it purgeable.
     */
    @Test
    fun `a live session is never purged however old it is`() {
        val live = session(activeAccount(), expiresAt = now.plus(1, ChronoUnit.DAYS))

        job.purge()

        assertNotNull(
            sessions.findByTokenHash(live),
            "purging a live session would sign somebody out overnight - here, someone " +
                "unable to clock in when they arrive"
        )
    }
}
