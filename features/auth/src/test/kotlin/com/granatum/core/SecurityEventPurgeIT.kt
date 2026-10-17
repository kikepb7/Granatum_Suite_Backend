package com.granatum.core

import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.database.entities.SecurityEventEntity
import com.granatum.core.infrastructure.database.repositories.SecurityEventRepository
import com.granatum.core.infrastructure.database.repositories.SecurityEventPurgeRepository
import com.granatum.core.scheduling.SecurityEventPurgeJob
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

/** Retention of security events (declared debt of feature 002, closed). */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class SecurityEventPurgeIT : BaseAuthIT() {

    @Autowired lateinit var events: SecurityEventRepository
    @Autowired lateinit var purgeRepository: SecurityEventPurgeRepository

    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    @Test
    fun `events past the retention period go, the rest stay`() {
        val account = UUID.randomUUID()
        events.save(SecurityEventEntity(accountId = account, type = SecurityEventType.LOGIN_SUCCEEDED, occurredAt = now.minus(731, ChronoUnit.DAYS)))
        events.save(SecurityEventEntity(accountId = account, type = SecurityEventType.LOGIN_FAILED, occurredAt = now.minus(729, ChronoUnit.DAYS)))

        SecurityEventPurgeJob(purgeRepository, days = 730, clock = Clock.fixed(now, ZoneOffset.UTC)).purge()

        assertEquals(listOf(SecurityEventType.LOGIN_FAILED), events.findAllByAccountIdOrderByOccurredAtDesc(account).map { it.type })
    }

    /** The table stays append-only for everything that writes events. */
    @Test
    fun `the events repository still offers no way to delete`() {
        assertEquals(
            emptyList(),
            SecurityEventRepository::class.java.methods.map { it.name }.filter { it.startsWith("delete") || it.startsWith("remove") }
        )
    }
}
