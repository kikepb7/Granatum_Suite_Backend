package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.ClientEventIdReusedException
import com.granatum.core.domain.exception.ClockSkewException
import com.granatum.core.domain.exception.DuplicateDocumentException
import com.granatum.core.domain.exception.EmployeeInactiveException
import com.granatum.core.domain.exception.EmployeeNotFoundException
import com.granatum.core.domain.exception.ExportBusyException
import com.granatum.core.domain.exception.ShiftImmutableException
import com.granatum.core.domain.exception.ShiftNotInProgressException
import com.granatum.core.domain.exception.ShiftNotFinishedException
import com.granatum.core.domain.exception.ShiftNotFoundException
import com.granatum.core.domain.exception.ShiftAlreadyInProgressException
import com.granatum.core.domain.exception.VerificationFileTooLargeException
import com.granatum.core.domain.exception.BreakOpenOnCloseException
import com.granatum.core.domain.exception.BreakNotOpenException
import com.granatum.core.domain.exception.BreakAlreadyOpenException
import com.granatum.core.domain.exception.CorrectionRequestNotFoundException
import com.granatum.core.domain.exception.CorrectionRequestAlreadyResolvedException
import com.granatum.core.domain.exception.LocationNotCorrectableException
import com.granatum.core.domain.exception.InconsistentValuesException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps this module's business exceptions to the stable error codes declared in
 * `specs/001-timetracking/contracts/README.md`. Single error shape
 * `{ "code", "message" }`, emitted from here and never built in a controller
 * (principle VIII).
 *
 * ## Why the explicit [Order]
 *
 * These exceptions extend the base types in `common`, and
 * `CommonExceptionHandler` has a handler for `InvalidOperationException` that
 * matches every one of them. Spring consults `@RestControllerAdvice` beans in
 * order and takes the first with a matching method, so without an explicit
 * precedence the generic handler could win and turn every conflict into
 * `400 INVALID_OPERATION` - silently violating the contract, which requires
 * 409 and 422 with specific codes.
 *
 * `HIGHEST_PRECEDENCE` makes the module-specific mapping authoritative. The
 * integration tests assert the HTTP status and the `code` of each error, so a
 * regression here fails the build rather than quietly degrading the API.
 *
 * No message in this class may carry a document number or a location: error
 * bodies reach clients and logs alike (principle VI).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class TimetrackingExceptionHandler {

    // --- 404 ---------------------------------------------------------------

    @ExceptionHandler(ShiftNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onShiftNotFound(e: ShiftNotFoundException) = error("SHIFT_NOT_FOUND", e.message)

    @ExceptionHandler(EmployeeNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onEmployeeNotFound(e: EmployeeNotFoundException) = error("EMPLOYEE_NOT_FOUND", e.message)

    @ExceptionHandler(CorrectionRequestNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onCorrectionRequestNotFound(e: CorrectionRequestNotFoundException) = error("REQUEST_NOT_FOUND", e.message)

    // --- 409: conflicts with the current state -----------------------------

    @ExceptionHandler(ShiftAlreadyInProgressException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onShiftAlreadyInProgress(e: ShiftAlreadyInProgressException) = error("SHIFT_ALREADY_IN_PROGRESS", e.message)

    @ExceptionHandler(BreakAlreadyOpenException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onBreakAlreadyOpen(e: BreakAlreadyOpenException) = error("BREAK_ALREADY_OPEN", e.message)

    @ExceptionHandler(BreakNotOpenException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onBreakNotOpen(e: BreakNotOpenException) = error("BREAK_NOT_OPEN", e.message)

    @ExceptionHandler(BreakOpenOnCloseException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onBreakOpenOnClose(e: BreakOpenOnCloseException) =
        error("BREAK_OPEN_ON_CLOSE", e.message)

    @ExceptionHandler(ShiftNotInProgressException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onShiftNotInProgress(e: ShiftNotInProgressException) = error("SHIFT_NOT_IN_PROGRESS", e.message)

    @ExceptionHandler(ShiftNotFinishedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onShiftNotFinished(e: ShiftNotFinishedException) =
        error("SHIFT_NOT_FINISHED", e.message)

    @ExceptionHandler(ShiftImmutableException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onShiftImmutable(e: ShiftImmutableException) = error("SHIFT_IMMUTABLE", e.message)

    @ExceptionHandler(CorrectionRequestAlreadyResolvedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onCorrectionRequestAlreadyResolved(e: CorrectionRequestAlreadyResolvedException) =
        error("REQUEST_ALREADY_RESOLVED", e.message)

    @ExceptionHandler(EmployeeInactiveException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onEmployeeInactive(e: EmployeeInactiveException) = error("EMPLOYEE_INACTIVE", e.message)

    @ExceptionHandler(DuplicateDocumentException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onDuplicateDocument(e: DuplicateDocumentException) =
        error("DUPLICATE_DOCUMENT", e.message)

    @ExceptionHandler(ClientEventIdReusedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onClientEventIdReused(e: ClientEventIdReusedException) =
        error("CLIENT_EVENT_ID_REUSED", e.message)

    // --- 422: semantically invalid ----------------------------------------

    @ExceptionHandler(InconsistentValuesException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onInconsistentValues(e: InconsistentValuesException) =
        error("INCONSISTENT_VALUES", e.message)

    @ExceptionHandler(LocationNotCorrectableException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onLocationNotCorrectable(e: LocationNotCorrectableException) =
        error("LOCATION_NOT_CORRECTABLE", e.message)

    @ExceptionHandler(ClockSkewException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onClockSkew(e: ClockSkewException) = error("CLOCK_SKEW", e.message)

    // --- Export (feature 003) ---------------------------------------------

    /** `Retry-After` tells a well-behaved client when to come back (D-002). */
    @ExceptionHandler(ExportBusyException::class)
    fun onExportBusy(e: ExportBusyException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(error("EXPORT_BUSY", e.message!!))

    @ExceptionHandler(VerificationFileTooLargeException::class)
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
    fun onVerificationFileTooLarge(e: VerificationFileTooLargeException) =
        error("FILE_TOO_LARGE", e.message!!)

    private fun error(code: String, message: String) = mapOf(
        "code" to code,
        "message" to message
    )
}
