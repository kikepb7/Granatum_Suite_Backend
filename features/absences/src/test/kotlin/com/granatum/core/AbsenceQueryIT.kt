package com.granatum.core

import com.granatum.core.domain.exception.AbsenceNotFoundException
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
import kotlin.test.assertTrue

/** The calendar and the balance (feature 007, US5): FR-014, FR-015, FR-018. */
@Testcontainers
@SpringBootTest(classes = [AbsencesTestApplication::class])
class AbsenceQueryIT : BaseAbsencesIT() {

    @Autowired lateinit var service: AbsenceService

    private val year get() = LocalDate.now(ZoneId.of("Europe/Madrid")).year + 2
    private fun day(month: Int, d: Int) = LocalDate.of(year, month, d)
    private fun vacation(from: LocalDate, to: LocalDate) = NewAbsence(AbsenceType.VACATION, null, from, to, null)

    @Test
    fun `the staff calendar brings every person's absences that touch the range, by status if asked`() {
        val one = directory.active()
        val other = directory.active()
        val august = service.request(one, vacation(day(7, 28), day(8, 3)))
        val approved = service.request(other, vacation(day(8, 20), day(8, 22)))
        service.approve(approved.id, UUID.randomUUID())
        service.request(other, vacation(day(9, 10), day(9, 11)))

        val inAugust = service.search(null, null, day(8, 1), day(8, 31)).map { it.id }
        assertTrue(august.id in inAugust && approved.id in inAugust, "both touch August")
        assertEquals(2, inAugust.count { it == august.id || it == approved.id })

        val approvedOnly = service.search(null, AbsenceStatus.APPROVED, day(8, 1), day(8, 31)).map { it.id }
        assertTrue(approved.id in approvedOnly && august.id !in approvedOnly)

        val ofOne = service.search(one, null, day(1, 1), day(12, 31))
        assertTrue(ofOne.all { it.employeeId == one } && ofOne.isNotEmpty())
    }

    @Test
    fun `whoever may only see their own gets not-found for someone else's`() {
        val one = directory.active()
        val othersAbsence = service.request(directory.active(), vacation(day(10, 1), day(10, 2)))

        assertFailsWith<AbsenceNotFoundException> { service.get(othersAbsence.id, one, seesAll = false) }
        assertEquals(othersAbsence.id, service.get(othersAbsence.id, one, seesAll = true).id)
    }

    @Test
    fun `the entitlement set by the ADMIN replaces the default`() {
        val employee = directory.active()

        service.setEntitlement(employee, year, 17, UUID.randomUUID())
        assertEquals(17, service.balance(employee, year).entitlement)

        service.setEntitlement(employee, year, 20, UUID.randomUUID())
        assertEquals(20, service.balance(employee, year).entitlement)
        assertEquals(30, service.balance(employee, year + 1).entitlement, "other years keep the default")
    }

    @Test
    fun `holidays across new year use each year's own balance`() {
        val employee = directory.active()
        service.setEntitlement(employee, year + 1, 3, UUID.randomUUID())

        service.request(employee, vacation(LocalDate.of(year, 12, 28), LocalDate.of(year + 1, 1, 2)))

        assertEquals(4, service.balance(employee, year).pending)
        assertEquals(2, service.balance(employee, year + 1).pending)
        assertEquals(1, service.balance(employee, year + 1).available)
    }
}
