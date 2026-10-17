package com.granatum.core

import com.granatum.core.domain.exception.AbsenceNotFoundException
import com.granatum.core.domain.exception.AbsenceNotModifiableException
import com.granatum.core.domain.model.AbsenceStatus
import com.granatum.core.domain.model.AbsenceType
import com.granatum.core.service.AbsenceService
import com.granatum.core.service.NewAbsence
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Cancelling (feature 007, US4): FR-012. */
@Testcontainers
@SpringBootTest(classes = [AbsencesTestApplication::class])
class AbsenceCancellationIT : BaseAbsencesIT() {

    @Autowired lateinit var service: AbsenceService

    private val today get() = LocalDate.now(ZoneId.of("Europe/Madrid"))

    private fun vacation(from: LocalDate, to: LocalDate) = NewAbsence(AbsenceType.VACATION, null, from, to, null)

    @Test
    fun `a pending request of one's own is cancelled and its days come back`() {
        val employee = directory.active()
        val requested = service.request(employee, vacation(today.plusDays(30), today.plusDays(34)))

        val cancelled = service.cancel(requested.id, employee)

        assertEquals(AbsenceStatus.CANCELLED, cancelled.status)
        assertNotNull(cancelled.cancelledAt)
        assertEquals(0, service.balance(employee, today.plusDays(30).year).pending)
    }

    @Test
    fun `approved holidays that start tomorrow can still be cancelled`() {
        val employee = directory.active()
        val requested = service.request(employee, vacation(today.plusDays(1), today.plusDays(2)))
        service.approve(requested.id, UUID.randomUUID())

        assertEquals(AbsenceStatus.CANCELLED, service.cancel(requested.id, employee).status)
    }

    @Test
    fun `approved holidays that have started cannot`() {
        val employee = directory.active()
        val ongoing = service.record(UUID.randomUUID(), employee, vacation(today.minusDays(1), today.plusDays(3)))

        assertFailsWith<AbsenceNotModifiableException> { service.cancel(ongoing.id, employee) }
    }

    @Test
    fun `a rejected request is not cancelled`() {
        val employee = directory.active()
        val requested = service.request(employee, vacation(today.plusDays(40), today.plusDays(41)))
        service.reject(requested.id, UUID.randomUUID(), "No")

        val e = assertFailsWith<AbsenceNotModifiableException> { service.cancel(requested.id, employee) }
        assertEquals("Esa ausencia ya está rechazada", e.message)
    }

    /** Not 403: an EMPLOYEE must not learn that someone else's id exists. */
    @Test
    fun `someone else's absence is not found`() {
        val employee = directory.active()
        val requested = service.request(employee, vacation(today.plusDays(50), today.plusDays(51)))

        assertFailsWith<AbsenceNotFoundException> { service.cancel(requested.id, directory.active()) }
    }
}
