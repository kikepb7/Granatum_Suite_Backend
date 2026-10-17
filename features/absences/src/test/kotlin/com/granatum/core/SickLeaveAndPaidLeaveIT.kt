package com.granatum.core

import com.granatum.core.domain.exception.AbsenceNotModifiableException
import com.granatum.core.domain.exception.InvalidAbsenceDataException
import com.granatum.core.domain.exception.InvalidAbsenceRangeException
import com.granatum.core.domain.exception.OwnAbsenceResolutionException
import com.granatum.core.domain.model.AbsenceStatus
import com.granatum.core.domain.model.AbsenceType
import com.granatum.core.domain.model.LeaveCause
import com.granatum.core.service.AbsenceService
import com.granatum.core.service.NewAbsence
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Sick leave and paid leave (feature 007, US3): FR-002, FR-006 to FR-008, FR-017, SC-005. */
@Testcontainers
@SpringBootTest(classes = [AbsencesTestApplication::class])
class SickLeaveAndPaidLeaveIT : BaseAbsencesIT() {

    @Autowired lateinit var service: AbsenceService
    @Autowired lateinit var jdbc: JdbcTemplate

    private val today get() = LocalDate.now(ZoneId.of("Europe/Madrid"))
    private val manager = UUID.randomUUID()

    private fun sickLeave(from: LocalDate, to: LocalDate? = null, comment: String? = null) =
        NewAbsence(AbsenceType.SICK_LEAVE, null, from, to, comment)

    @Test
    fun `a MANAGER records a sick leave - approved, open, and no holidays used`() {
        val employee = directory.active()

        val recorded = service.record(manager, employee, sickLeave(today))

        assertEquals(AbsenceStatus.APPROVED, recorded.status)
        assertEquals(manager, recorded.resolvedBy)
        assertNull(recorded.to)
        assertEquals(30, service.balance(employee, today.year).available)
    }

    /** FR-007, SC-005: a comment on a sick leave would be health data. */
    @Test
    fun `a sick leave with a comment is refused, and the database refuses it too`() {
        val employee = directory.active()

        assertFailsWith<InvalidAbsenceDataException> {
            service.record(manager, employee, sickLeave(today, comment = "Gripe"))
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                """
                INSERT INTO absences (id, employee_id, type, from_date, status, comment, requested_by, requested_at,
                                      resolved_by, resolved_at)
                VALUES (gen_random_uuid(), ?, 'SICK_LEAVE', current_date, 'APPROVED', 'Gripe', ?, now(), ?, now())
                """.trimIndent(),
                employee, manager, manager
            )
        }
    }

    /** V32 rewrote the CHECKs: the Spanish values are refused now. */
    @Test
    fun `the database refuses the old Spanish values`() {
        val employee = directory.active()

        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                """
                INSERT INTO absences (id, employee_id, type, from_date, status, requested_by, requested_at,
                                      resolved_by, resolved_at)
                VALUES (gen_random_uuid(), ?, 'BAJA_MEDICA', current_date, 'APROBADA', ?, now(), ?, now())
                """.trimIndent(),
                employee, manager, manager
            )
        }
    }

    @Test
    fun `nobody records or requests their own sick leave`() {
        val employee = directory.active()

        assertFailsWith<OwnAbsenceResolutionException> { service.record(employee, employee, sickLeave(today)) }
        assertFailsWith<OwnAbsenceResolutionException> { service.request(employee, sickLeave(today, today.plusDays(2))) }
    }

    @Test
    fun `the discharge closes an open sick leave, once`() {
        val employee = directory.active()
        val open = service.record(manager, employee, sickLeave(today.minusDays(5)))

        val closed = service.discharge(open.id, manager, today)

        assertEquals(today, closed.to)
        assertFailsWith<AbsenceNotModifiableException> { service.discharge(open.id, manager, today.plusDays(1)) }
    }

    @Test
    fun `a discharge before the start of the leave is refused`() {
        val employee = directory.active()
        val open = service.record(manager, employee, sickLeave(today))

        assertFailsWith<InvalidAbsenceRangeException> { service.discharge(open.id, manager, today.minusDays(1)) }
    }

    @Test
    fun `paid leave needs its cause, is pending and uses no holidays`() {
        val employee = directory.active()

        val leave = service.request(
            employee, NewAbsence(AbsenceType.LEAVE, LeaveCause.FAMILY_DEATH, today, today.plusDays(1), null)
        )

        assertEquals(AbsenceStatus.PENDING, leave.status)
        assertEquals(LeaveCause.FAMILY_DEATH, leave.cause)
        assertEquals(30, service.balance(employee, today.year).available)
    }

    @Test
    fun `a cause belongs to paid leave and only to it`() {
        val employee = directory.active()

        assertFailsWith<InvalidAbsenceDataException> {
            service.request(employee, NewAbsence(AbsenceType.LEAVE, null, today, today, null))
        }
        assertFailsWith<InvalidAbsenceDataException> {
            service.request(employee, NewAbsence(AbsenceType.VACATION, LeaveCause.OTHER, today.plusDays(1), today.plusDays(1), null))
        }
    }

    @Test
    fun `only a sick leave may be recorded without an end`() {
        assertFailsWith<InvalidAbsenceRangeException> {
            service.record(manager, directory.active(), NewAbsence(AbsenceType.VACATION, null, today, null, null))
        }
    }

    /** FR-011: regularising past holidays on someone's behalf. */
    @Test
    fun `a MANAGER may record past holidays, approved`() {
        val employee = directory.active()

        val past = service.record(
            manager, employee, NewAbsence(AbsenceType.VACATION, null, today.minusDays(10), today.minusDays(8), null)
        )

        assertEquals(AbsenceStatus.APPROVED, past.status)
    }
}
