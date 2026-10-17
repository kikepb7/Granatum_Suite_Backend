package com.granatum.core.api.exception_handling

import com.granatum.core.domain.contract.InsufficientStockException
import com.granatum.core.domain.contract.MaterialMissingException
import com.granatum.core.domain.exception.DateInPastException
import com.granatum.core.domain.exception.EventFinishedException
import com.granatum.core.domain.exception.EventNotFoundException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Error codes of feature 011 (specs/011-events/contracts/README.md). Ahead of
 * `CommonExceptionHandler`, like every module's advice: [EventNotFoundException] is also a
 * `NotFoundException`, and the specific code must win.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class EventsExceptionHandler {

    @ExceptionHandler(EventNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onEventNotFound(e: EventNotFoundException) = error("EVENT_NOT_FOUND", e.message)

    @ExceptionHandler(MaterialMissingException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMaterialMissing(e: MaterialMissingException) = error("MATERIAL_NOT_FOUND", e.message)

    /** Says how many units there are, so the person can ask for that many (FR-005). */
    @ExceptionHandler(InsufficientStockException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onInsufficientStock(e: InsufficientStockException) = mapOf(
        "code" to "INSUFFICIENT_STOCK",
        "message" to e.message,
        "available" to e.available
    )

    @ExceptionHandler(EventFinishedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onEventFinished(e: EventFinishedException) = error("EVENT_FINISHED", e.message)

    @ExceptionHandler(DateInPastException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onDateInPast(e: DateInPastException) = error("DATE_IN_PAST", e.message)

    private fun error(code: String, message: String?) = mapOf("code" to code, "message" to message)
}
