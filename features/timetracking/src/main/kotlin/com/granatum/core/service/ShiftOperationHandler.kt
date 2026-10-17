package com.granatum.core.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.granatum.core.api.dto.ShiftDto
import com.granatum.core.domain.type.ShiftOperationType
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Wraps the four fichaje operations in the two things they all need: a clock
 * check and idempotency.
 *
 * Sits between controller and service rather than inside either. The controller
 * must stay free of logic (principle VIII), and `ShiftService` holds the
 * domain rules - while idempotency is neither: it is about how an operation
 * *arrives*, not what it means. Putting it here keeps the service testable
 * without any of this machinery, which is why its unit tests need no
 * idempotency setup at all.
 *
 * Owns its own [ObjectMapper] for the same reason
 * [com.granatum.core.infrastructure.database.ShiftValuesJson] does: the
 * stored response body is persisted data that has to round-trip exactly on a
 * replay, and in Boot 4 the autoconfigured bean lives in a module that is not
 * on this one's classpath.
 */
@Service
class ShiftOperationHandler(
    private val clockSkewValidator: ClockSkewValidator,
    private val idempotency: ShiftIdempotencyService
) {

    private val json: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    /**
     * Runs [operation] unless this [clientEventId] has already been handled, in
     * which case the original response is returned verbatim.
     *
     * The clock is validated **before** the idempotency lookup on purpose: a
     * request with an implausible time is malformed, and storing it as a
     * processed event would make the bad timestamp permanent and replayable.
     */
    fun run(
        clientEventId: UUID,
        employeeId: UUID,
        operationType: ShiftOperationType,
        occurredAt: Instant,
        responseStatus: Int,
        operation: () -> ShiftDto
    ): ShiftDto {
        clockSkewValidator.validate(occurredAt)

        val requestBody = fingerprintOf(clientEventId, operationType, occurredAt)

        idempotency.previousResponse(clientEventId, requestBody)?.let { previous ->
            return json.readValue(previous.body, ShiftDto::class.java)
        }

        val result = operation()

        idempotency.record(
            clientEventId = clientEventId,
            employeeId = employeeId,
            shiftId = result.id,
            operationType = operationType,
            occurredAt = occurredAt,
            requestBody = requestBody,
            responseStatus = responseStatus,
            responseBody = json.writeValueAsString(result)
        )

        return result
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
    private fun fingerprintOf(
        clientEventId: UUID,
        operationType: ShiftOperationType,
        occurredAt: Instant
    ): String = "$clientEventId|$operationType|$occurredAt"
}
