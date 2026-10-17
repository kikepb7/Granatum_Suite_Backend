package com.granatum.core

import com.granatum.core.domain.event.DomainNotice
import com.granatum.core.domain.event.NoticeType
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.repositories.NotificationRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Pageable
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who gets told (feature 008, US2): FR-003, FR-004, FR-008. Events are published
 * the way the features publish them - through Spring - inside or outside a
 * transaction.
 */
@Testcontainers
@SpringBootTest(classes = [NotificationsTestApplication::class])
class NotificationRecipientsIT : BaseNotificationsIT() {

    @Autowired lateinit var publisher: ApplicationEventPublisher
    @Autowired lateinit var notifications: NotificationRepository
    @Autowired lateinit var transactions: TransactionTemplate

    @BeforeEach
    fun noRoles() = roleDirectory.forgetAll()

    private fun types(recipient: UUID) =
        notifications.findAllByRecipientIdOrderByCreatedAtDescIdDesc(recipient, Pageable.ofSize(100)).map { it.type to it.referenceId }

    private fun publishInTransaction(notice: DomainNotice) = transactions.executeWithoutResult { publisher.publishEvent(notice) }

    @Test
    fun `a pending absence reaches every MANAGER and ADMIN but not whoever asked`() {
        val admin = roleDirectory.with(Role.ADMIN)
        val manager = roleDirectory.with(Role.MANAGER)
        val requestingManager = roleDirectory.with(Role.MANAGER)
        val employee = roleDirectory.with(Role.EMPLOYEE)
        val absence = UUID.randomUUID()

        publishInTransaction(DomainNotice(NoticeType.ABSENCE_PENDING, absence, ownerId = requestingManager, authorId = requestingManager))

        assertEquals(listOf(NoticeType.ABSENCE_PENDING to absence), types(admin))
        assertEquals(listOf(NoticeType.ABSENCE_PENDING to absence), types(manager))
        assertTrue(types(requestingManager).isEmpty(), "nobody is told about their own request")
        assertTrue(types(employee).isEmpty())
    }

    @Test
    fun `a pending correction reaches the managers too`() {
        val manager = roleDirectory.with(Role.MANAGER)
        val correction = UUID.randomUUID()

        publishInTransaction(DomainNotice(NoticeType.CORRECTION_PENDING, correction, authorId = UUID.randomUUID()))

        assertEquals(listOf(NoticeType.CORRECTION_PENDING to correction), types(manager))
    }

    /** fallbackExecution: published with no transaction around it, the notice is still stored. */
    @Test
    fun `a notice published outside a transaction is stored too`() {
        val person = UUID.randomUUID()
        val shift = UUID.randomUUID()

        publisher.publishEvent(DomainNotice(NoticeType.SHIFT_WITHOUT_CLOCK_OUT, shift, ownerId = person))

        assertEquals(listOf(NoticeType.SHIFT_WITHOUT_CLOCK_OUT to shift), types(person))
    }

    @Test
    fun `resolutions and shift notices reach the person they concern`() {
        val person = UUID.randomUUID()
        val absence = UUID.randomUUID()
        val shift = UUID.randomUUID()

        publishInTransaction(DomainNotice(NoticeType.ABSENCE_APPROVED, absence, ownerId = person, authorId = UUID.randomUUID()))
        publishInTransaction(DomainNotice(NoticeType.SHIFT_WITHOUT_CLOCK_OUT, shift, ownerId = person))

        assertEquals(setOf(NoticeType.ABSENCE_APPROVED to absence, NoticeType.SHIFT_WITHOUT_CLOCK_OUT to shift), types(person).toSet())
    }

    @Test
    fun `with no MANAGER or ADMIN nobody is told, and that is not an error`() {
        publishInTransaction(DomainNotice(NoticeType.ABSENCE_PENDING, UUID.randomUUID(), authorId = UUID.randomUUID()))
    }

    /** FR-008: no notice of something that did not happen. */
    @Test
    fun `an event published in a transaction that rolls back leaves no notice`() {
        val admin = roleDirectory.with(Role.ADMIN)

        runCatching {
            transactions.executeWithoutResult {
                publisher.publishEvent(DomainNotice(NoticeType.ABSENCE_PENDING, UUID.randomUUID(), authorId = UUID.randomUUID()))
                error("the operation fails after publishing")
            }
        }

        assertTrue(types(admin).isEmpty())
    }

    /**
     * FR-008, second half: the operation is already committed, so a notice
     * that cannot be created is lost - it must not turn into an error for
     * whoever made the request.
     */
    @Test
    fun `a notice that fails to be created does not reach the caller`() {
        roleDirectory.fail = true

        publishInTransaction(DomainNotice(NoticeType.ABSENCE_PENDING, UUID.randomUUID(), authorId = UUID.randomUUID()))
        // Without a transaction (fallbackExecution) the listener runs inside
        // publishEvent itself: this is the case the catch exists for - after a
        // commit, Spring already contains the exception.
        publisher.publishEvent(DomainNotice(NoticeType.ABSENCE_PENDING, UUID.randomUUID(), authorId = UUID.randomUUID()))
    }

    /** FR-007, D-002: the forgotten clock-out check sees the same open shift again and again. */
    @Test
    fun `the same notice published twice is stored once`() {
        val person = UUID.randomUUID()
        val shift = UUID.randomUUID()

        repeat(2) { publishInTransaction(DomainNotice(NoticeType.SHIFT_WITHOUT_CLOCK_OUT, shift, ownerId = person)) }

        assertEquals(1, types(person).size)
    }
}
