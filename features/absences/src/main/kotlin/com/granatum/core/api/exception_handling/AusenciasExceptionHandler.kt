package com.granatum.core.api.exception_handling

import com.granatum.core.domain.exception.AusenciaNoEncontradaException
import com.granatum.core.domain.exception.AusenciaNoModificableException
import com.granatum.core.domain.exception.AusenciaSolapadaException
import com.granatum.core.domain.exception.DatosAusenciaInvalidosException
import com.granatum.core.domain.exception.PersonaAusenciaInactivaException
import com.granatum.core.domain.exception.PersonaAusenciaNoEncontradaException
import com.granatum.core.domain.exception.RangoAusenciaInvalidoException
import com.granatum.core.domain.exception.ResolucionPropiaAusenciaException
import com.granatum.core.domain.exception.SaldoVacacionesInsuficienteException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Error codes of feature 007 (specs/007-absences/contracts/README.md). */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class AusenciasExceptionHandler {

    @ExceptionHandler(AusenciaNoEncontradaException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onNoEncontrada(e: AusenciaNoEncontradaException) = error("AUSENCIA_NO_ENCONTRADA", e.message)

    @ExceptionHandler(AusenciaSolapadaException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onSolapada(e: AusenciaSolapadaException) = error("AUSENCIA_SOLAPADA", e.message)

    @ExceptionHandler(SaldoVacacionesInsuficienteException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onSaldo(e: SaldoVacacionesInsuficienteException) = error("SALDO_INSUFICIENTE", e.message)

    @ExceptionHandler(RangoAusenciaInvalidoException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onRango(e: RangoAusenciaInvalidoException) = error("RANGO_INVALIDO", e.message)

    @ExceptionHandler(DatosAusenciaInvalidosException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun onDatos(e: DatosAusenciaInvalidosException) = error("DATOS_AUSENCIA_INVALIDOS", e.message)

    @ExceptionHandler(AusenciaNoModificableException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onNoModificable(e: AusenciaNoModificableException) = error("AUSENCIA_NO_MODIFICABLE", e.message)

    /** Two resolutions of the same absence at once: the second loses (research.md D-004). */
    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onConcurrente(e: ObjectOptimisticLockingFailureException) =
        error("AUSENCIA_NO_MODIFICABLE", "Otra persona ha cambiado esa ausencia a la vez; vuelve a consultarla")

    @ExceptionHandler(ResolucionPropiaAusenciaException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun onPropia(e: ResolucionPropiaAusenciaException) = error("RESOLUCION_PROPIA", e.message)

    @ExceptionHandler(PersonaAusenciaNoEncontradaException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun onPersonaNoEncontrada(e: PersonaAusenciaNoEncontradaException) = error("EMPLEADO_NO_ENCONTRADO", e.message)

    @ExceptionHandler(PersonaAusenciaInactivaException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun onPersonaInactiva(e: PersonaAusenciaInactivaException) = error("EMPLEADO_INACTIVO", e.message)

    private fun error(code: String, message: String?): Map<String, String?> = mapOf("code" to code, "message" to message)
}
