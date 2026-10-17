package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.NotificationNotFoundException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Error codes of feature 008. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class NotificationsExceptionHandler {

    @ExceptionHandler(NotificationNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onNotFound(e: NotificationNotFoundException): Map<String, String?> =
        mapOf("code" to "NOTIFICATION_NOT_FOUND", "message" to e.message)
}
