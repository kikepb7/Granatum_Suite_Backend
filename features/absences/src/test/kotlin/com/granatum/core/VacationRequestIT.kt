package com.granatum.core

import com.granatum.core.domain.exception.AbsenceEmployeeInactiveException
import com.granatum.core.domain.exception.AbsenceEmployeeNotFoundException
import com.granatum.core.domain.exception.AbsenceNotModifiableException
import com.granatum.core.domain.exception.InsufficientVacationBalanceException
import com.granatum.core.domain.exception.InvalidAbsenceRangeException
import com.granatum.core.domain.exception.OwnAbsenceResolutionException
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

/** Holidays requested and resolved (feature 007, US1): FR-001, FR-003 to FR-005, FR-011, FR-013 to FR-016. */
@Testcontainers
@SpringBootTest(classes = [AbsencesTestApplication::class])
class VacationRequestIT : BaseAbsencesIT() {

    @Autowired lateinit var service: AbsenceService

    private val today: LocalDate get() = LocalDate.now(ZoneId.of("Europe/Madrid"))
    /** Far enough ahead to be in the future and inside a single year. */
    private val year: Int get() = today.year + 1

    private fun day(month: Int, d: Int) = LocalDate.of(year, month, d)

    private fun vacation(from: LocalDate, to: LocalDate) =
        NewAbsence(AbsenceType.VACATION, cause = null, from = from, to = to, comment = "Verano")

    @Test
    fun `a request is pending and shows in the balance as pending`() {
        val employee = directory.active()

        val absence = service.request(employee, vacation(day(8, 3), day(8, 14)))

        assertEquals(AbsenceStatus.PENDING, absence.status)
        val balance = service.balance(employee, year)
        assertEquals(30, balance.entitlement)
        assertEquals(12, balance.pending)
        assertEquals(18, balance.available)
    }

    @Test
    fun `a MANAGER approves it, and who and when are recorded`() {
        val employee = directory.active()
        val manager = UUID.randomUUID()
        val requested = service.request(employee, vacation(day(8, 3), day(8, 14)))

        val approved = service.approve(requested.id, manager)

        assertEquals(AbsenceStatus.APPROVED, approved.status)
        assertEquals(manager, approved.resolvedBy)
        assertNotNull(approved.resolvedAt)
        assertEquals(12, service.balance(employee, year).approved)
    }

    @Test
    fun `rejecting records the reason and gives the days back`() {
        val employee = directory.active()
        val requested = service.request(employee, vacation(day(8, 3), day(8, 14)))

        val rejected = service.reject(requested.id, UUID.randomUUID(), "Coincide con la feria")

        assertEquals(AbsenceStatus.REJECTED, rejected.status)
        assertEquals("Coincide con la feria", rejected.rejectionReason)
        assertEquals(30, service.balance(employee, year).available)
    }

    @Test
    fun `nobody resolves their own request`() {
        val manager = directory.active()
        val own = service.request(manager, vacation(day(8, 3), day(8, 5)))

        assertFailsWith<OwnAbsenceResolutionException> { service.approve(own.id, manager) }
        assertFailsWith<OwnAbsenceResolutionException> { service.reject(own.id, manager, "no") }
    }

    @Test
    fun `a resolved request cannot be resolved again`() {
        val employee = directory.active()
        val requested = service.request(employee, vacation(day(8, 3), day(8, 5)))
        service.approve(requested.id, UUID.randomUUID())

        assertFailsWith<AbsenceNotModifiableException> { service.reject(requested.id, UUID.randomUUID(), "tarde") }
        assertFailsWith<AbsenceNotModifiableException> { service.approve(requested.id, UUID.randomUUID()) }
    }

    @Test
    fun `more days than are left is refused`() {
        val employee = directory.active()
        service.request(employee, vacation(day(6, 1), day(6, 25)))

        val e = assertFailsWith<InsufficientVacationBalanceException> {
            service.request(employee, vacation(day(9, 1), day(9, 6)))
        }
        assertEquals(true, e.message!!.contains("disponibles: 5"))
    }

    @Test
    fun `a person cannot ask for holidays that have already started`() {
        val employee = directory.active()

        assertFailsWith<InvalidAbsenceRangeException> {
            service.request(employee, vacation(today.minusDays(1), today.plusDays(2)))
        }
    }

    @Test
    fun `an end before the start, or more than 366 days, is refused`() {
        val employee = directory.active()

        assertFailsWith<InvalidAbsenceRangeException> { service.request(employee, vacation(day(8, 14), day(8, 3))) }
        assertFailsWith<InvalidAbsenceRangeException> {
            service.request(employee, vacation(day(1, 1), day(1, 1).plusDays(366)))
        }
    }

    @Test
    fun `nobody unknown or inactive gets a new absence`() {
        assertFailsWith<AbsenceEmployeeNotFoundException> {
            service.request(UUID.randomUUID(), vacation(day(8, 3), day(8, 5)))
        }
        assertFailsWith<AbsenceEmployeeInactiveException> {
            service.request(directory.inactive(), vacation(day(8, 3), day(8, 5)))
        }
    }
}
