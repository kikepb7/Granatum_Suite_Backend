package com.granatum.core.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.granatum.core.api.dto.FichajeDto
import com.granatum.core.domain.type.TipoOperacionFichaje
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Wraps the four fichaje operations in the two things they all need: a clock
 * check and idempotency.
 *
 * Sits between controller and service rather than inside either. The controller
 * must stay free of logic (principle VIII), and `FichajeService` holds the
 * domain rules - while idempotency is neither: it is about how an operation
 * *arrives*, not what it means. Putting it here keeps the service testable
 * without any of this machinery, which is why its unit tests need no
 * idempotency setup at all.
 *
 * Owns its own [ObjectMapper] for the same reason
 * [com.granatum.core.infrastructure.database.ValoresFichajeJson] does: the
 * stored response body is persisted data that has to round-trip exactly on a
 * replay, and in Boot 4 the autoconfigured bean lives in a module that is not
 * on this one's classpath.
 */
@Service
class OperacionFichajeHandler(
    private val validadorReloj: ValidadorReloj,
    private val idempotencia: IdempotenciaService
) {

    private val json: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    /**
     * Runs [operacion] unless this [clientEventId] has already been handled, in
     * which case the original response is returned verbatim.
     *
     * The clock is validated **before** the idempotency lookup on purpose: a
     * request with an implausible time is malformed, and storing it as a
     * processed event would make the bad timestamp permanent and replayable.
     */
    fun ejecutar(
        clientEventId: UUID,
        empleadoId: UUID,
        tipoOperacion: TipoOperacionFichaje,
        occurredAt: Instant,
        estadoRespuesta: Int,
        operacion: () -> FichajeDto
    ): FichajeDto {
        validadorReloj.validar(occurredAt)

        val cuerpoPeticion = huellaDe(clientEventId, tipoOperacion, occurredAt)

        idempotencia.respuestaPrevia(clientEventId, cuerpoPeticion)?.let { previa ->
            return json.readValue(previa.cuerpo, FichajeDto::class.java)
        }

        val resultado = operacion()

        idempotencia.registrar(
            clientEventId = clientEventId,
            empleadoId = empleadoId,
            fichajeId = resultado.id,
            tipoOperacion = tipoOperacion,
            occurredAt = occurredAt,
            cuerpoPeticion = cuerpoPeticion,
            estadoRespuesta = estadoRespuesta,
            cuerpoRespuesta = json.writeValueAsString(resultado)
        )

        return resultado
    }

    /**
     * The canonical form the fingerprint is taken over.
     *
     * Built from the fields that identify the operation rather than from the
     * raw request body: the raw body's key order and whitespace are not stable
     * across clients, so two identical retries could hash differently and be
     * rejected as a reused key - turning the safety check into the bug it was
     * meant to catch.
     */
    private fun huellaDe(
        clientEventId: UUID,
        tipoOperacion: TipoOperacionFichaje,
        occurredAt: Instant
    ): String = "$clientEventId|$tipoOperacion|$occurredAt"
}
