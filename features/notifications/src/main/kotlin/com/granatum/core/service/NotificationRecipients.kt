package com.granatum.core.service

import com.granatum.core.domain.contract.RoleDirectory
import com.granatum.core.domain.event.DomainNotice
import com.granatum.core.domain.event.NoticeType
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.Role
import org.springframework.stereotype.Component

/**
 * Who hears about each kind of notice (feature 008, FR-001 to FR-005,
 * specs/008-notifications/contracts/README.md).
 *
 * Pending requests go to whoever can resolve them, never to whoever made
 * them; everything else goes to the person it concerns.
 */
@Component
class NotificationRecipients(private val roles: RoleDirectory) {

    fun of(notice: DomainNotice): Set<EntityId> = when (notice.type) {
        NoticeType.CORRECTION_PENDING, NoticeType.ABSENCE_PENDING ->
            roles.employeesWithRole(setOf(Role.MANAGER, Role.ADMIN)) - setOfNotNull(notice.authorId)
        NoticeType.SHIFT_WITHOUT_CLOCK_OUT, NoticeType.SHIFT_INCOMPLETE,
        NoticeType.CORRECTION_APPROVED, NoticeType.CORRECTION_REJECTED,
        NoticeType.ABSENCE_APPROVED, NoticeType.ABSENCE_REJECTED ->
            setOfNotNull(notice.ownerId)
    }
}
