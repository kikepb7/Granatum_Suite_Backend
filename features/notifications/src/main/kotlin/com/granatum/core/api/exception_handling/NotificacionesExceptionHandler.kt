package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.NotificacionNoEncontradaException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Error codes of feature 008. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class NotificacionesExceptionHandler {

    @ExceptionHandler(NotificacionNoEncontradaException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onNoEncontrada(e: NotificacionNoEncontradaException): Map<String, String?> =
        mapOf("code" to "NOTIFICACION_NO_ENCONTRADA", "message" to e.message)
}
