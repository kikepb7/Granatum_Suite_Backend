package com.granatum.core.api.exception_handling

import com.granatum.core.domain.contract.MaterialInexistenteException
import com.granatum.core.domain.contract.StockInsuficienteException
import com.granatum.core.domain.exception.EventoFinalizadoException
import com.granatum.core.domain.exception.EventoNotFoundException
import com.granatum.core.domain.exception.FechaPasadaException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Error codes of feature 011 (specs/011-events/contracts/README.md). Ahead of
 * `CommonExceptionHandler`, like every module's advice: [EventoNotFoundException] is also a
 * `NotFoundException`, and the specific code must win.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class EventosExceptionHandler {

    @ExceptionHandler(EventoNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onEventoNotFound(e: EventoNotFoundException) = error("EVENTO_NOT_FOUND", e.message)

    @ExceptionHandler(MaterialInexistenteException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onMaterialInexistente(e: MaterialInexistenteException) = error("MATERIAL_NOT_FOUND", e.message)

    /** Says how many units there are, so the person can ask for that many (FR-005). */
    @ExceptionHandler(StockInsuficienteException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onStockInsuficiente(e: StockInsuficienteException) = mapOf(
        "code" to "STOCK_INSUFICIENTE",
        "message" to e.message,
        "disponible" to e.disponible
    )

    @ExceptionHandler(EventoFinalizadoException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onFinalizado(e: EventoFinalizadoException) = error("EVENTO_FINALIZADO", e.message)

    @ExceptionHandler(FechaPasadaException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun onFechaPasada(e: FechaPasadaException) = error("FECHA_PASADA", e.message)

    private fun error(code: String, message: String?) = mapOf("code" to code, "message" to message)
}
