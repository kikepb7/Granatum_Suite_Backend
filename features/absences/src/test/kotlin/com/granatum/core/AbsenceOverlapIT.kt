package com.granatum.core

import com.granatum.core.domain.exception.AbsenceOverlapException
import com.granatum.core.domain.model.AbsenceStatus
import com.granatum.core.domain.model.AbsenceType
import com.granatum.core.domain.model.LeaveCause
import com.granatum.core.infrastructure.database.repositories.AbsenceRepository
import com.granatum.core.service.AbsenceService
import com.granatum.core.service.NewAbsence
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** No two standing absences of one person overlap (feature 007, US2): FR-009, SC-002. */
@Testcontainers
@SpringBootTest(classes = [AbsencesTestApplication::class])
class AbsenceOverlapIT : BaseAbsencesIT() {

    @Autowired lateinit var service: AbsenceService
    @Autowired lateinit var repository: AbsenceRepository

    private val year get() = LocalDate.now(ZoneId.of("Europe/Madrid")).year + 1
    private fun day(month: Int, d: Int) = LocalDate.of(year, month, d)

    private fun vacation(from: LocalDate, to: LocalDate) =
        NewAbsence(AbsenceType.VACATION, null, from, to, null)

    private fun leave(from: LocalDate, to: LocalDate) =
        NewAbsence(AbsenceType.LEAVE, LeaveCause.HOUSE_MOVE, from, to, null)

    @Test
    fun `an approved or a pending absence blocks the days it covers`() {
        val employee = directory.active()
        val approved = service.request(employee, vacation(day(8, 3), day(8, 14)))
        service.approve(approved.id, UUID.randomUUID())
        service.request(employee, vacation(day(9, 1), day(9, 2)))

        assertFailsWith<AbsenceOverlapException> { service.request(employee, leave(day(8, 10), day(8, 11))) }
        assertFailsWith<AbsenceOverlapException> { service.request(employee, leave(day(9, 2), day(9, 3))) }
        // Touching edges overlap: both ends are included.
        assertFailsWith<AbsenceOverlapException> { service.request(employee, leave(day(8, 14), day(8, 14))) }
    }

    @Test
    fun `rejected or cancelled days are free again`() {
        val employee = directory.active()
        val rejected = service.request(employee, vacation(day(8, 3), day(8, 14)))
        service.reject(rejected.id, UUID.randomUUID(), "No")
        val cancelled = service.request(employee, vacation(day(10, 5), day(10, 9)))
        service.cancel(cancelled.id, employee)

        service.request(employee, vacation(day(8, 3), day(8, 14)))
        service.request(employee, vacation(day(10, 5), day(10, 9)))
    }

    @Test
    fun `an open sick leave blocks every day after its start`() {
        val employee = directory.active()
        service.record(UUID.randomUUID(), employee, NewAbsence(AbsenceType.SICK_LEAVE, null, day(3, 1), null, null))

        assertFailsWith<AbsenceOverlapException> { service.request(employee, vacation(day(11, 2), day(11, 3))) }
        service.request(employee, leave(day(2, 26), day(2, 27)))
    }

    /**
     * SC-002. Without the per-person advisory lock every thread reads "no
     * overlap" before any has written, and several are stored (research.md
     * D-002).
     */
    @Test
    fun `twenty simultaneous overlapping requests of one person admit exactly one`() {
        val employee = directory.active()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(20)

        val futures = (1..20).map { i ->
            pool.submit<Result<Any>> {
                start.await()
                runCatching { service.request(employee, leave(day(5, 1 + i % 3), day(5, 10))) }
            }
        }
        start.countDown()
        val results = futures.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, results.count { it.isSuccess }, results.filter { it.isFailure }.map { it.exceptionOrNull()?.javaClass?.simpleName }.toString())
        results.filter { it.isFailure }.forEach { assertEquals<Class<*>>(AbsenceOverlapException::class.java, it.exceptionOrNull()!!.javaClass) }
        assertEquals(
            1,
            repository.findForEmployeeInRange(employee, day(5, 1), day(5, 10), listOf(AbsenceStatus.PENDING, AbsenceStatus.APPROVED)).size
        )
    }

    @Test
    fun `two different people do not block each other`() {
        val one = directory.active()
        val other = directory.active()

        service.request(one, vacation(day(7, 1), day(7, 5)))
        service.request(other, vacation(day(7, 1), day(7, 5)))
    }
}
