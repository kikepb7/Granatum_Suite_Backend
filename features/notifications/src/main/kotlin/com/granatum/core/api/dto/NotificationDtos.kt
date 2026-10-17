package com.granatum.core.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.granatum.core.domain.event.NoticeType
import com.granatum.core.domain.model.Notification
import java.time.Instant
import java.util.UUID

/** Feature 008, specs/008-notifications/contracts/README.md. */
data class NotificationResponse(
    val id: UUID,
    val type: NoticeType,
    val referenceId: UUID,
    val message: String,
    val createdAt: Instant,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val readAt: Instant?
) {
    companion object {
        fun from(n: Notification) = NotificationResponse(n.id, n.type, n.referenceId, n.message, n.createdAt, n.readAt)
    }
}

data class NotificationUnreadCountResponse(val total: Long)
