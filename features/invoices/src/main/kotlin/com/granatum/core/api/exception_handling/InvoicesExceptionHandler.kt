package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.FacturacionException
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

    @ExceptionHandler(FacturacionException::class)
    fun onFacturacion(e: FacturacionException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(e.estado).body(mapOf("code" to e.codigo, "message" to e.message))
}
