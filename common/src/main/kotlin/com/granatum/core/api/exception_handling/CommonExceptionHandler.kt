package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.InvalidOperationException
import com.granatum.core.domain.exception.NotFoundException
import com.granatum.core.domain.exception.UnauthorizedException
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

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

    // --- 400 VALIDACION ----------------------------------------------------
    //
    // The framework's own validation and binding errors, which until feature
    // 003 nothing in the product handled: they came out with Spring's default
    // error body - not the contract's {code, message} shape (principle VIII) -
    // and in `dev`, where devtools enables stack traces and binding errors,
    // with the whole trace and the REJECTED VALUE in the response. A malformed
    // email came back to the client; an over-long password in change-password
    // would have come back in clear.
    //
    // Each message names the FIELD or PARAMETER and never the value: the value
    // can be a password, and error bodies reach clients and logs alike
    // (principle VI). `ValidacionFormatoIT` asserts both the code and the
    // absence of the value, for every family of route.
    //
    // No precedence clash with the modules' @Order(HIGHEST_PRECEDENCE) advice:
    // these are framework exceptions, not subclasses of any module's, so no
    // other handler matches them.

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onCuerpoNoValido(e: MethodArgumentNotValidException) = validacion(
        "Campos no validos: " +
            e.bindingResult.fieldErrors.map { it.field }.distinct().sorted().joinToString(", ")
    )

    @ExceptionHandler(HandlerMethodValidationException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onParametrosNoValidos(e: HandlerMethodValidationException) = validacion(
        "Parametros no validos: " +
            e.parameterValidationResults
                .mapNotNull { it.methodParameter.parameterName }
                .distinct().sorted().joinToString(", ")
    )

    @ExceptionHandler(MissingServletRequestParameterException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onParametroAusente(e: MissingServletRequestParameterException) =
        validacion("Falta el parametro obligatorio '${e.parameterName}'")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onTipoIncorrecto(e: MethodArgumentTypeMismatchException) =
        validacion("El parametro '${e.name}' no tiene un formato valido")

    /**
     * Deliberately ignores `e.message`: for a malformed JSON body Jackson's
     * message quotes the offending input, which is exactly what must not be
     * echoed.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onCuerpoIlegible(e: HttpMessageNotReadableException) =
        validacion("El cuerpo de la peticion no es legible o no es un JSON valido")

    private fun validacion(message: String) = mapOf("code" to "VALIDACION", "message" to message)
}
