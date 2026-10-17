package com.granatum.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.granatum.core.api.dto.NotificationResponse
import com.granatum.core.domain.event.NoticeType
import com.granatum.core.domain.exception.NotificationNotFoundException
import com.granatum.core.domain.model.Notification
import com.granatum.core.infrastructure.database.repositories.NotificationRepository
import com.granatum.core.service.NotificationService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A person's inbox (feature 008, US4): FR-006, FR-009, FR-010, SC-003, SC-004. */
@Testcontainers
@SpringBootTest(classes = [NotificationsTestApplication::class])
class NotificationInboxIT : BaseNotificationsIT() {

    @Autowired lateinit var service: NotificationService
    @Autowired lateinit var repository: NotificationRepository
    @Autowired lateinit var transactions: TransactionTemplate
    // A local mapper: Boot 4 serves JSON with Jackson 3 and registers no
    // Jackson 2 ObjectMapper bean. The wire format itself is checked end to
    // end in app (NotificacionesDePuntaAPuntaIT); this checks the DTO's shape.
    private val json = ObjectMapper().findAndRegisterModules()

    private fun create(recipient: UUID, type: NoticeType = NoticeType.ABSENCE_PENDING, minutesAgo: Long = 0): UUID {
        val id = UUID.randomUUID()
        transactions.executeWithoutResult {
            repository.insertIfAbsent(id, recipient, type.name, UUID.randomUUID(), Instant.now().minus(minutesAgo, ChronoUnit.MINUTES))
        }
        return id
    }

    @Test
    fun `the inbox is newest first, and can show only the unread`() {
        val person = UUID.randomUUID()
        val old = create(person, minutesAgo = 30)
        val recent = create(person, minutesAgo = 1)
        service.markRead(old, person)

        assertEquals(listOf(recent, old), service.inbox(person, unreadOnly = false).map { it.id })
        assertEquals(listOf(recent), service.inbox(person, unreadOnly = true).map { it.id })
    }

    @Test
    fun `it holds at most 100`() {
        val person = UUID.randomUUID()
        repeat(105) { create(person, minutesAgo = it.toLong()) }

        assertEquals(100, service.inbox(person, false).size)
        assertEquals(105, service.unreadCount(person))
    }

    @Test
    fun `marking one read lowers the count, marking all leaves none`() {
        val person = UUID.randomUUID()
        val one = create(person)
        repeat(2) { create(person) }

        service.markRead(one, person)
        assertEquals(2, service.unreadCount(person))
        service.markRead(one, person) // again: nothing changes, no error

        service.markAllRead(person)
        assertEquals(0, service.unreadCount(person))
    }

    /** FR-010, SC-004: someone else's notice does not exist for you. */
    @Test
    fun `someone else's notice cannot be seen or marked`() {
        val owner = UUID.randomUUID()
        val othersNotice = create(owner)
        val other = UUID.randomUUID()

        assertTrue(service.inbox(other, false).isEmpty())
        assertFailsWith<NotificationNotFoundException> { service.markRead(othersNotice, other) }
        service.markAllRead(other)
        assertEquals(1, service.unreadCount(owner), "marking all of yours touches nobody else's")
    }

    /** FR-006, SC-003: a fixed text per type, and only ids besides. */
    @Test
    fun `every notice reads as a fixed message with ids and nothing else`() {
        val person = UUID.randomUUID()
        NoticeType.entries.forEach { create(person, it) }

        val sent = service.inbox(person, false)
        assertEquals(NoticeType.entries.toSet(), sent.map { it.type }.toSet())
        sent.forEach {
            assertEquals(Notification.MESSAGES.getValue(it.type), it.message)
            val fields = json.readTree(json.writeValueAsString(NotificationResponse.from(it))).fieldNames().asSequence().toSet()
            assertEquals(setOf("id", "type", "referenceId", "message", "createdAt", "readAt"), fields)
        }
    }
}
