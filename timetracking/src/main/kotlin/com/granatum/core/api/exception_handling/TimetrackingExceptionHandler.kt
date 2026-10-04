package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.ClientEventIdReutilizadoException
import com.granatum.core.domain.exception.DesviacionRelojException
import com.granatum.core.domain.exception.DocumentoDuplicadoException
import com.granatum.core.domain.exception.EmpleadoInactivoException
import com.granatum.core.domain.exception.EmpleadoNotFoundException
import com.granatum.core.domain.exception.FichajeInmutableException
import com.granatum.core.domain.exception.FichajeNoEnCursoException
import com.granatum.core.domain.exception.FichajeNoFinalizadoException
import com.granatum.core.domain.exception.FichajeNotFoundException
import com.granatum.core.domain.exception.FichajeYaEnCursoException
import com.granatum.core.domain.exception.PausaAbiertaAlCerrarException
import com.granatum.core.domain.exception.PausaNoAbiertaException
import com.granatum.core.domain.exception.PausaYaAbiertaException
import com.granatum.core.domain.exception.SolicitudNotFoundException
import com.granatum.core.domain.exception.SolicitudYaResueltaException
import com.granatum.core.domain.exception.UbicacionNoCorregibleException
import com.granatum.core.domain.exception.ValoresIncoherentesException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
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

    @ExceptionHandler(FichajeNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onFichajeNotFound(e: FichajeNotFoundException) = error("FICHAJE_NOT_FOUND", e.message)

    @ExceptionHandler(EmpleadoNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onEmpleadoNotFound(e: EmpleadoNotFoundException) = error("EMPLEADO_NOT_FOUND", e.message)

    @ExceptionHandler(SolicitudNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onSolicitudNotFound(e: SolicitudNotFoundException) = error("SOLICITUD_NOT_FOUND", e.message)

    // --- 409: conflicts with the current state -----------------------------

    @ExceptionHandler(FichajeYaEnCursoException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onFichajeYaEnCurso(e: FichajeYaEnCursoException) = error("FICHAJE_YA_EN_CURSO", e.message)

    @ExceptionHandler(PausaYaAbiertaException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onPausaYaAbierta(e: PausaYaAbiertaException) = error("PAUSA_YA_ABIERTA", e.message)

    @ExceptionHandler(PausaNoAbiertaException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onPausaNoAbierta(e: PausaNoAbiertaException) = error("PAUSA_NO_ABIERTA", e.message)

    @ExceptionHandler(PausaAbiertaAlCerrarException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onPausaAbiertaAlCerrar(e: PausaAbiertaAlCerrarException) =
        error("PAUSA_ABIERTA_AL_CERRAR", e.message)

    @ExceptionHandler(FichajeNoEnCursoException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onFichajeNoEnCurso(e: FichajeNoEnCursoException) = error("FICHAJE_NO_EN_CURSO", e.message)

    @ExceptionHandler(FichajeNoFinalizadoException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onFichajeNoFinalizado(e: FichajeNoFinalizadoException) =
        error("FICHAJE_NO_FINALIZADO", e.message)

    @ExceptionHandler(FichajeInmutableException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onFichajeInmutable(e: FichajeInmutableException) = error("FICHAJE_INMUTABLE", e.message)

    @ExceptionHandler(SolicitudYaResueltaException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onSolicitudYaResuelta(e: SolicitudYaResueltaException) =
        error("SOLICITUD_YA_RESUELTA", e.message)

    @ExceptionHandler(EmpleadoInactivoException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onEmpleadoInactivo(e: EmpleadoInactivoException) = error("EMPLEADO_INACTIVO", e.message)

    @ExceptionHandler(DocumentoDuplicadoException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onDocumentoDuplicado(e: DocumentoDuplicadoException) =
        error("DOCUMENTO_DUPLICADO", e.message)

    @ExceptionHandler(ClientEventIdReutilizadoException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onClientEventIdReutilizado(e: ClientEventIdReutilizadoException) =
        error("CLIENT_EVENT_ID_REUTILIZADO", e.message)

    // --- 422: semantically invalid ----------------------------------------

    @ExceptionHandler(ValoresIncoherentesException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onValoresIncoherentes(e: ValoresIncoherentesException) =
        error("VALORES_INCOHERENTES", e.message)

    @ExceptionHandler(UbicacionNoCorregibleException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onUbicacionNoCorregible(e: UbicacionNoCorregibleException) =
        error("UBICACION_NO_CORREGIBLE", e.message)

    @ExceptionHandler(DesviacionRelojException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onDesviacionReloj(e: DesviacionRelojException) = error("DESVIACION_RELOJ", e.message)

    private fun error(code: String, message: String) = mapOf(
        "code" to code,
        "message" to message
    )
}
