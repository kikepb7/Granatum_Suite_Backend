package com.granatum.core.service

import com.granatum.core.domain.exception.ClientEventIdReusedException
import com.granatum.core.domain.type.ShiftOperationType
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Makes the fichaje operations safe to resend (FR-024, FR-025).
 *
 * The mobile app works offline and retries, so the same operation can arrive
 * more than once. A retry must not create a second shift, and it must get the
 * **same answer** as the first attempt.
 *
 * ## Why the response is stored rather than recomputed
 *
 * Returning the fichaje's *current* state on a retry looks equivalent and is
 * not: if the shift moved on between the first send and the retry - a break
 * started, the day closed - the retry would answer something different from the
 * original. That breaks the promise precisely in the case the feature exists
 * for, which is why `shift_events` keeps the original status and body.
 *
 * ## Why the request fingerprint
 *
 * Comparing only the key would mean a client that reuses one for a different
 * operation receives *another operation's* response and never finds out.
 * Comparing the body too turns that into an explicit conflict, which is a
 * client bug worth surfacing rather than absorbing.
 */
@Service
class ShiftIdempotencyService(
    private val recorder: ShiftEventRecorder
) {

    /** What a replayed operation answers with. */
    data class StoredResponse(val status: Int, val body: String)

    /**
     * Returns the stored response when [clientEventId] has already been
     * processed with the same body, or `null` when the operation is new and the
     * caller should go ahead.
     *
     * @throws ClientEventIdReusedException when the key has been seen with
     *   a different body.
     */
    fun previousResponse(clientEventId: UUID, requestBody: String): StoredResponse? {
        val event = recorder.findByClientEventId(clientEventId) ?: return null

        if (event.requestFingerprint != recorder.fingerprint(requestBody)) {
            throw ClientEventIdReusedException()
        }

        return StoredResponse(event.responseStatus, event.responseBody)
    }

    /** Records a freshly processed operation so a later resend finds it. */
    fun record(
        clientEventId: UUID,
        employeeId: UUID,
        shiftId: UUID?,
        operationType: ShiftOperationType,
        occurredAt: Instant,
        requestBody: String,
        responseStatus: Int,
        responseBody: String
    ) {
        recorder.record(
            clientEventId = clientEventId,
            employeeId = employeeId,
            shiftId = shiftId,
            operationType = operationType,
            occurredAt = occurredAt,
            requestBody = requestBody,
            responseStatus = responseStatus,
            responseBody = responseBody
        )
    }
}
