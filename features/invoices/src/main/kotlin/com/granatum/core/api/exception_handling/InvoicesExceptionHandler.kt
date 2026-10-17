package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.InvoicingException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps the invoicing exceptions to `{ "code", "message" }` with their status
 * (principle VIII). `HIGHEST_PRECEDENCE`, like the other modules' advice, so a
 * generic handler in `common` never wins over the specific code.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class InvoicesExceptionHandler {

    @ExceptionHandler(InvoicingException::class)
    fun onInvoicing(e: InvoicingException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(e.status).body(mapOf("code" to e.code, "message" to e.message))
}
