package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.InvalidOperationException
import com.granatum.core.domain.exception.NotFoundException
import com.granatum.core.domain.exception.UnauthorizedException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Cross-cutting exception mappings shared by every feature module.
 * Feature-specific handlers (e.g. `InventoryExceptionHandler`) extend this
 * pattern for their own domain exceptions.
 */
@RestControllerAdvice
class CommonExceptionHandler {

    @ExceptionHandler(NotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onNotFound(e: NotFoundException) = mapOf(
        "code" to "NOT_FOUND",
        "message" to e.message
    )

    @ExceptionHandler(ForbiddenException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onForbidden(e: ForbiddenException) = mapOf(
        "code" to "FORBIDDEN",
        "message" to e.message
    )

    @ExceptionHandler(UnauthorizedException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun onUnauthorized(e: UnauthorizedException) = mapOf(
        "code" to "UNAUTHORIZED",
        "message" to e.message
    )

    @ExceptionHandler(InvalidOperationException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onInvalidOperation(e: InvalidOperationException) = mapOf(
        "code" to "INVALID_OPERATION",
        "message" to e.message
    )
}
