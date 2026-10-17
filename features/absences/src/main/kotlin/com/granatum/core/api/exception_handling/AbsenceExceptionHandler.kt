package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.AbsenceEmployeeInactiveException
import com.granatum.core.domain.exception.AbsenceEmployeeNotFoundException
import com.granatum.core.domain.exception.AbsenceNotFoundException
import com.granatum.core.domain.exception.AbsenceNotModifiableException
import com.granatum.core.domain.exception.AbsenceOverlapException
import com.granatum.core.domain.exception.InsufficientVacationBalanceException
import com.granatum.core.domain.exception.InvalidAbsenceDataException
import com.granatum.core.domain.exception.InvalidAbsenceRangeException
import com.granatum.core.domain.exception.OwnAbsenceResolutionException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Error codes of feature 007 (specs/007-absences/contracts/README.md, docs/english-naming.md). */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class AbsenceExceptionHandler {

    @ExceptionHandler(AbsenceNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onNotFound(e: AbsenceNotFoundException) = error("ABSENCE_NOT_FOUND", e.message)

    @ExceptionHandler(AbsenceOverlapException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onOverlap(e: AbsenceOverlapException) = error("ABSENCE_OVERLAP", e.message)

    @ExceptionHandler(InsufficientVacationBalanceException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onBalance(e: InsufficientVacationBalanceException) = error("INSUFFICIENT_BALANCE", e.message)

    @ExceptionHandler(InvalidAbsenceRangeException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onRange(e: InvalidAbsenceRangeException) = error("INVALID_RANGE", e.message)

    @ExceptionHandler(InvalidAbsenceDataException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onData(e: InvalidAbsenceDataException) = error("INVALID_ABSENCE_DATA", e.message)

    @ExceptionHandler(AbsenceNotModifiableException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onNotModifiable(e: AbsenceNotModifiableException) = error("ABSENCE_NOT_MODIFIABLE", e.message)

    /** Two resolutions of the same absence at once: the second loses (research.md D-004). */
    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConcurrent(e: ObjectOptimisticLockingFailureException) =
        error("ABSENCE_NOT_MODIFIABLE", "Otra persona ha cambiado esa ausencia a la vez; vuelve a consultarla")

    @ExceptionHandler(OwnAbsenceResolutionException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onOwn(e: OwnAbsenceResolutionException) = error("OWN_RESOLUTION", e.message)

    @ExceptionHandler(AbsenceEmployeeNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onEmployeeNotFound(e: AbsenceEmployeeNotFoundException) = error("EMPLOYEE_NOT_FOUND", e.message)

    @ExceptionHandler(AbsenceEmployeeInactiveException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onEmployeeInactive(e: AbsenceEmployeeInactiveException) = error("EMPLOYEE_INACTIVE", e.message)

    private fun error(code: String, message: String?): Map<String, String?> = mapOf("code" to code, "message" to message)
}
