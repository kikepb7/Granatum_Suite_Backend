package com.granatum.core.api.controllers

import com.granatum.core.api.dto.NotificationResponse
import com.granatum.core.api.dto.NotificationUnreadCountResponse
import com.granatum.core.api.util.requestUserId
import com.granatum.core.service.NotificationService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Everyone's own inbox (feature 008). The recipient is always the token subject. */
@RestController
@RequestMapping("/api/notifications")
class NotificationController(private val service: NotificationService) {

    @GetMapping
    fun inbox(@RequestParam(defaultValue = "false") unreadOnly: Boolean): List<NotificationResponse> =
        service.inbox(requestUserId, unreadOnly).map(NotificationResponse::from)

    @GetMapping("/unread")
    fun unread(): NotificationUnreadCountResponse = NotificationUnreadCountResponse(service.unreadCount(requestUserId))

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markRead(@PathVariable id: UUID) = service.markRead(id, requestUserId)

    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markAllRead() {
        service.markAllRead(requestUserId)
    }
}
