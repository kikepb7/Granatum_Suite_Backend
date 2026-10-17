package com.granatum.core

import com.granatum.core.domain.exception.ClientEventIdReusedException
import com.granatum.core.domain.type.ShiftOperationType
import com.granatum.core.infrastructure.database.entities.ShiftEventEntity
import com.granatum.core.service.ShiftIdempotencyService
import com.granatum.core.service.ShiftEventRecorder
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The three outcomes of an idempotency lookup, isolated from the database.
 */
class ShiftIdempotencyServiceTest {

    private val recorder = mockk<ShiftEventRecorder>()
    private val service = ShiftIdempotencyService(recorder)

    private val key = UUID.randomUUID()
    private val body = "$key|CLOCK_IN|2026-10-05T07:00:00Z"

    private fun eventWith(fingerprint: String) = ShiftEventEntity(
        clientEventId = key,
        employeeId = UUID.randomUUID(),
        shiftId = UUID.randomUUID(),
        operationType = ShiftOperationType.CLOCK_IN,
        occurredAt = Instant.parse("2026-10-05T07:00:00Z"),
        receivedAt = Instant.parse("2026-10-05T07:30:00Z"),
        requestFingerprint = fingerprint,
        responseStatus = 201,
        responseBody = """{"id":"abc"}"""
    )

    @Test
    fun `an unseen key lets the operation through`() {
        every { recorder.findByClientEventId(key) } returns null

        assertNull(
            service.previousResponse(key, body),
            "null means 'new': the caller should go ahead"
        )
    }

    @Test
    fun `a replayed key with the same body returns the stored response`() {
        val fingerprint = "huella-estable"
        every { recorder.findByClientEventId(key) } returns eventWith(fingerprint)
        every { recorder.fingerprint(body) } returns fingerprint

        val previous = service.previousResponse(key, body)

        assertEquals(201, previous?.status)
        assertEquals("""{"id":"abc"}""", previous?.body)
    }

    /**
     * The case the fingerprint exists for. Comparing only the key would hand
     * this caller **another operation's** response, and nothing would reveal it.
     */
    @Test
    fun `the same key with a different body is a conflict`() {
        every { recorder.findByClientEventId(key) } returns eventWith("huella-original")
        every { recorder.fingerprint(body) } returns "huella-distinta"

        assertFailsWith<ClientEventIdReusedException> {
            service.previousResponse(key, body)
        }
    }
}
