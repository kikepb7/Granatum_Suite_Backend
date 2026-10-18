package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.SheetsApiException
import com.granatum.core.domain.port.SheetsTransientException
import com.granatum.core.domain.port.SheetsUnavailableException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps the sheets exceptions to `{ "code", "message" }` - plus the extra
 * fields some codes carry, like `spreadsheet` and `reason` - with their status
 * (principle VIII). `HIGHEST_PRECEDENCE`, like the other modules' advice.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class SheetsExceptionHandler {

    @ExceptionHandler(SheetsApiException::class)
    fun onSheets(e: SheetsApiException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(e.status).body(mapOf("code" to e.code, "message" to e.message) + e.extra)

    /** The server has no Google credentials (FR-005). */
    @ExceptionHandler(SheetsUnavailableException::class)
    fun onUnavailable(e: SheetsUnavailableException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(mapOf("code" to "SHEETS_UNAVAILABLE", "message" to e.message!!))

    /** Google did not answer while a request waited on it. */
    @ExceptionHandler(SheetsTransientException::class)
    fun onUnreachable(e: SheetsTransientException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
            mapOf("code" to "SHEETS_UNREACHABLE", "message" to "Google no responde en este momento. Inténtalo de nuevo en unos minutos.")
        )
}
