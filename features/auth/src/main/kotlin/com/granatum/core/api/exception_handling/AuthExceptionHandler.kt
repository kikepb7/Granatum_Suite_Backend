package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.InvalidCredentialsException
import com.granatum.core.domain.exception.InactiveEmployeeAccountException
import com.granatum.core.domain.exception.AccountNotFoundException
import com.granatum.core.domain.exception.AccountAlreadyExistsException
import com.granatum.core.domain.exception.EmailAlreadyRegisteredException
import com.granatum.core.domain.exception.EmployeeNotInDirectoryException
import com.granatum.core.domain.exception.WeakPasswordException
import com.granatum.core.domain.exception.InvalidRefreshTokenException
import com.granatum.core.domain.exception.VerificationBusyException
import com.granatum.core.domain.exception.InvalidBootstrapCodeException
import com.granatum.core.domain.exception.InvalidDocumentException
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
 * could win and turn `ACCOUNT_ALREADY_EXISTS` and `WEAK_PASSWORD` into
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
    @ExceptionHandler(InvalidCredentialsException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun onInvalidCredentials(e: InvalidCredentialsException) =
        error("INVALID_CREDENTIALS", e.message)

    @ExceptionHandler(InvalidRefreshTokenException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun onInvalidRefreshToken(e: InvalidRefreshTokenException) =
        error("INVALID_REFRESH_TOKEN", e.message)

    @ExceptionHandler(InactiveEmployeeAccountException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun onEmployeeInactive(e: InactiveEmployeeAccountException) =
        error("EMPLOYEE_INACTIVE", e.message)

    // --- 404 ---------------------------------------------------------------

    @ExceptionHandler(EmployeeNotInDirectoryException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onEmployeeNotFound(e: EmployeeNotInDirectoryException) =
        error("EMPLOYEE_NOT_FOUND", e.message)

    @ExceptionHandler(AccountNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onAccountNotFound(e: AccountNotFoundException) =
        error("ACCOUNT_NOT_FOUND", e.message)

    // --- 409 ---------------------------------------------------------------

    /**
     * 409 and not 400: it is the state of the system that blocks the operation,
     * not the shape of the request. The two codes stay distinct because the
     * `ADMIN`'s way out differs - reset in one case, use another address in the
     * other.
     */
    @ExceptionHandler(AccountAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onAccountAlreadyExists(e: AccountAlreadyExistsException) =
        error("ACCOUNT_ALREADY_EXISTS", e.message)

    @ExceptionHandler(EmailAlreadyRegisteredException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onEmailAlreadyRegistered(e: EmailAlreadyRegisteredException) =
        error("EMAIL_ALREADY_REGISTERED", e.message)

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
     * `requirements` holds enum names only - never a fragment of the password.
     */
    @ExceptionHandler(WeakPasswordException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onWeakPassword(e: WeakPasswordException): Map<String, Any?> = mapOf(
        "code" to "WEAK_PASSWORD",
        "message" to e.message,
        "requirements" to e.requirements.map { it.name }
    )

    // --- 503 ---------------------------------------------------------------

    /**
     * Returned with `Retry-After` because the condition is transient by
     * definition: the semaphore in `BoundedVerifier` is full. A client that
     * retries in a second will almost certainly get through, and telling it so
     * is cheaper than having it fall back to asking for the password again.
     */
    @ExceptionHandler(VerificationBusyException::class)
    fun onVerificationBusy(e: VerificationBusyException): ResponseEntity<Map<String, String?>> =
        ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(error("SERVICE_BUSY", e.message))

    // --- Sign-up of the owner (feature 005, US1) and onboarding (feature 009) ---

    @ExceptionHandler(InvalidBootstrapCodeException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onInvalidBootstrapCode(e: InvalidBootstrapCodeException) =
        error("INVALID_BOOTSTRAP_CODE", e.message)

    @ExceptionHandler(InvalidDocumentException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onInvalidDocument(e: InvalidDocumentException) =
        error("INVALID_DOCUMENT", e.message)

    private fun error(code: String, message: String?): Map<String, String?> =
        mapOf("code" to code, "message" to message)
}
