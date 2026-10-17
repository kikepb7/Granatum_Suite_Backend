package com.granatum.core.service

import com.granatum.core.domain.type.ShiftOperationType
import com.granatum.core.infrastructure.database.entities.ShiftEventEntity
import com.granatum.core.infrastructure.database.repositories.ShiftEventRepository
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Writes one row to the append-only event log per received operation.
 *
 * Required by constitution principle III (v1.1.0) for **every** fichaje
 * operation, not only for the idempotency of the offline story: this log is the
 * evidentiary record of the working-time register. What matters to a labour
 * inspection is what the person clocked and when, not the aggregated state.
 *
 * The projection tables (`shifts`, `breaks`) must at all times be derivable
 * from this log plus approved corrections. If a value in the projection is not
 * explained by an event or a correction, it was written through a path that
 * should not exist - which is what `StateDerivabilityIT` asserts.
 */
@Service
class ShiftEventRecorder(
    private val shiftEventRepository: ShiftEventRepository,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * Records [operationType] and returns the stored event.
     *
     * [occurredAt] is when it actually happened per the device and is what the
     * working-time computation uses; `receivedAt` is taken here and is never
     * used for computation (FR-025).
     */
    fun record(
        clientEventId: UUID,
        employeeId: UUID,
        shiftId: UUID?,
        operationType: ShiftOperationType,
        occurredAt: Instant,
        requestBody: String,
        responseStatus: Int,
        responseBody: String
    ): ShiftEventEntity =
        shiftEventRepository.save(
            ShiftEventEntity(
                clientEventId = clientEventId,
                employeeId = employeeId,
                shiftId = shiftId,
                operationType = operationType,
                occurredAt = occurredAt,
                receivedAt = clock.instant(),
                requestFingerprint = fingerprint(requestBody),
                responseStatus = responseStatus,
                responseBody = responseBody
            )
        )

    fun findByClientEventId(clientEventId: UUID): ShiftEventEntity? =
        shiftEventRepository.findByClientEventId(clientEventId)

    /**
     * SHA-256 of the request body. Stored so that the same idempotency key
     * arriving with a *different* body can be rejected as a conflict instead of
     * silently returning another operation's response - which is what would
     * happen if only the key were compared, and the client would never find out.
     */
    fun fingerprint(body: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
