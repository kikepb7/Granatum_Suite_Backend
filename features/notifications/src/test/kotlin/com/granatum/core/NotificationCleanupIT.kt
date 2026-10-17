package com.granatum.core

import com.granatum.core.domain.event.NoticeType
import com.granatum.core.infrastructure.database.repositories.NotificationRepository
import com.granatum.core.scheduling.NotificationCleanupJob
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

/** Old notices go (feature 008, US5): FR-011. */
@Testcontainers
@SpringBootTest(classes = [NotificationsTestApplication::class])
class NotificationCleanupIT : BaseNotificationsIT() {

    @Autowired lateinit var job: NotificationCleanupJob
    @Autowired lateinit var repository: NotificationRepository
    @Autowired lateinit var transactions: TransactionTemplate
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun create(person: UUID, daysAgo: Long, read: Boolean): UUID {
        val id = UUID.randomUUID()
        val at = Instant.now().minus(daysAgo, ChronoUnit.DAYS)
        transactions.executeWithoutResult {
            repository.insertIfAbsent(id, person, NoticeType.ABSENCE_APPROVED.name, UUID.randomUUID(), at)
        }
        if (read) jdbc.update("UPDATE notifications SET read_at = ? WHERE id = ?", Timestamp.from(at), id)
        return id
    }

    @Test
    fun `read ones after 90 days and any after 180 are deleted, the rest stay`() {
        val person = UUID.randomUUID()
        val oldRead = create(person, 91, read = true)
        val recentRead = create(person, 89, read = true)
        val unreadFrom100 = create(person, 100, read = false)
        val unreadFrom181 = create(person, 181, read = false)

        job.cleanUp()

        val remaining = repository.findAllByRecipientIdOrderByCreatedAtDescIdDesc(person, Pageable.ofSize(10)).map { it.id }.toSet()
        assertEquals(setOf(recentRead, unreadFrom100), remaining)
        assertEquals(false, oldRead in remaining || unreadFrom181 in remaining)
    }
}
