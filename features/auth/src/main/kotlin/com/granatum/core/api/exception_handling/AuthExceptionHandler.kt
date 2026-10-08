package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.CredencialesInvalidasException
import com.granatum.core.domain.exception.CuentaDeEmpleadoInactivoException
import com.granatum.core.domain.exception.CuentaNoEncontradaException
import com.granatum.core.domain.exception.CuentaYaExisteException
import com.granatum.core.domain.exception.EmailYaRegistradoException
import com.granatum.core.domain.exception.EmpleadoNoEncontradoEnDirectorioException
import com.granatum.core.domain.exception.PasswordDebilException
import com.granatum.core.domain.exception.TokenRenovacionInvalidoException
import com.granatum.core.domain.exception.VerificacionSaturadaException
import com.granatum.core.domain.exception.CodigoArranqueInvalidoException
import com.granatum.core.domain.exception.DocumentoInvalidoException
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
 * `specs/002-auth/contracts/README.md`. Single error shape `{ "code", "message" }`,
 * emitted from here and never built in a controller (principle VIII).
 *
 * ## Why the explicit [Order]
 *
 * These exceptions extend the base types in `common`, and `CommonExceptionHandler`
 * has a handler for `InvalidOperationException` that matches several of them.
 * Spring consults `@RestControllerAdvice` beans in order and takes the first
 * with a matching method, so without an explicit precedence the generic handler
 * could win and turn `CUENTA_YA_EXISTE` and `PASSWORD_DEBIL` into
 * `400 INVALID_OPERATION` - silently violating the contract, which requires 409
 * and 422 with specific codes. This already happened once, in feature 001.
 *
 * `HIGHEST_PRECEDENCE` makes this mapping authoritative, and the integration
 * tests assert both the status and the `code` of each error so a regression
 * fails the build instead of quietly degrading the API.
 *
 * ## No message carries a secret
 *
 * Error bodies reach clients and logs alike (principle VI): no email address,
 * no password, no token, and no fragment of any of them.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class AuthExceptionHandler {

    // --- 401 ---------------------------------------------------------------

    /**
     * Unknown email, wrong password, dismissed person and locked account all
     * arrive here as the same exception and leave as the same body. That is
     * FR-003: anything else turns sign-in into an oracle for which addresses
     * exist.
     */
    @ExceptionHandler(CredencialesInvalidasException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun onCredencialesInvalidas(e: CredencialesInvalidasException) =
        error("CREDENCIALES_INVALIDAS", e.message)

    @ExceptionHandler(TokenRenovacionInvalidoException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun onTokenRenovacionInvalido(e: TokenRenovacionInvalidoException) =
        error("TOKEN_RENOVACION_INVALIDO", e.message)

    @ExceptionHandler(CuentaDeEmpleadoInactivoException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun onEmpleadoInactivo(e: CuentaDeEmpleadoInactivoException) =
        error("EMPLEADO_INACTIVO", e.message)

    // --- 404 ---------------------------------------------------------------

    @ExceptionHandler(EmpleadoNoEncontradoEnDirectorioException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onEmpleadoNoEncontrado(e: EmpleadoNoEncontradoEnDirectorioException) =
        error("EMPLEADO_NO_ENCONTRADO", e.message)

    @ExceptionHandler(CuentaNoEncontradaException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onCuentaNoEncontrada(e: CuentaNoEncontradaException) =
        error("CUENTA_NO_ENCONTRADA", e.message)

    // --- 409 ---------------------------------------------------------------

    /**
     * 409 and not 400: it is the state of the system that blocks the operation,
     * not the shape of the request. The two codes stay distinct because the
     * `ADMIN`'s way out differs - reset in one case, use another address in the
     * other.
     */
    @ExceptionHandler(CuentaYaExisteException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onCuentaYaExiste(e: CuentaYaExisteException) =
        error("CUENTA_YA_EXISTE", e.message)

    @ExceptionHandler(EmailYaRegistradoException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onEmailYaRegistrado(e: EmailYaRegistradoException) =
        error("EMAIL_YA_REGISTRADO", e.message)

    // --- 422 ---------------------------------------------------------------

    /**
     * The only error in this feature with a third field, and therefore a
     * **declared deviation from principle VIII**, which fixes the shape at
     * exactly `{code, message}`.
     *
     * FR-023 requires the rejection to explain *which* requirement failed, and a
     * client that wants to mark the offending form fields needs identifiers
     * rather than a sentence. It is recorded as a deviation in `plan.md`, in the
     * contract and in `README.md` instead of passing in silence, which is what
     * the constitution asks of an accepted departure.
     *
     * `requisitos` holds enum names only - never a fragment of the password.
     */
    @ExceptionHandler(PasswordDebilException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onPasswordDebil(e: PasswordDebilException): Map<String, Any?> = mapOf(
        "code" to "PASSWORD_DEBIL",
        "message" to e.message,
        "requisitos" to e.requisitos.map { it.name }
    )

    // --- 503 ---------------------------------------------------------------

    /**
     * Returned with `Retry-After` because the condition is transient by
     * definition: the semaphore in `VerificadorAcotado` is full. A client that
     * retries in a second will almost certainly get through, and telling it so
     * is cheaper than having it fall back to asking for the password again.
     */
    @ExceptionHandler(VerificacionSaturadaException::class)
    fun onVerificacionSaturada(e: VerificacionSaturadaException): ResponseEntity<Map<String, String?>> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(error("SERVICIO_SATURADO", e.message))

    // --- Sign-up of the owner (feature 005, US1) and onboarding (feature 009) ---

    @ExceptionHandler(CodigoArranqueInvalidoException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onCodigoArranqueInvalido(e: CodigoArranqueInvalidoException) =
        error("CODIGO_ARRANQUE_INVALIDO", e.message)

    @ExceptionHandler(DocumentoInvalidoException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onDocumentoInvalido(e: DocumentoInvalidoException) =
        error("DOCUMENTO_INVALIDO", e.message)

    private fun error(code: String, message: String?): Map<String, String?> =
        mapOf("code" to code, "message" to message)
}
