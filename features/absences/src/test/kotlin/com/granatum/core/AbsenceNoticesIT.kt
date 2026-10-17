package com.granatum.core

import com.granatum.core.domain.event.DomainNotice
import com.granatum.core.domain.event.NoticeType
import com.granatum.core.domain.model.AbsenceType
import com.granatum.core.service.AbsenceService
import com.granatum.core.service.NewAbsence
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

/** What this module tells the notices module (feature 008): FR-003, FR-005. */
@Testcontainers
@SpringBootTest(classes = [AbsencesTestApplication::class])
@RecordApplicationEvents
class AbsenceNoticesIT : BaseAbsencesIT() {

    @Autowired lateinit var service: AbsenceService
    @Autowired lateinit var events: ApplicationEvents

    private val year get() = LocalDate.now(ZoneId.of("Europe/Madrid")).year + 1
    private fun vacation(d: Int) = NewAbsence(AbsenceType.VACATION, null, LocalDate.of(year, 3, d), LocalDate.of(year, 3, d + 1), null)
    private fun notice(type: NoticeType, ref: UUID) =
        events.stream(DomainNotice::class.java).filter { it.type == type && it.referenceId == ref }.toList().single()

    @Test
    fun `a request is announced as pending with its author, and its resolution to the person`() {
        val employee = directory.active()
        val manager = UUID.randomUUID()

        val requested = service.request(employee, vacation(2))
        assertEquals(employee, notice(NoticeType.ABSENCE_PENDING, requested.id).authorId)

        service.approve(requested.id, manager)
        assertEquals(employee, notice(NoticeType.ABSENCE_APPROVED, requested.id).ownerId)

        val other = service.request(employee, vacation(10))
        service.reject(other.id, manager, "No")
        assertEquals(employee, notice(NoticeType.ABSENCE_REJECTED, other.id).ownerId)
    }

    @Test
    fun `an absence recorded on someone's behalf is announced to them as approved`() {
        val employee = directory.active()

        val recorded = service.record(UUID.randomUUID(), employee, NewAbsence(AbsenceType.SICK_LEAVE, null, LocalDate.now(), null, null))

        assertEquals(employee, notice(NoticeType.ABSENCE_APPROVED, recorded.id).ownerId)
    }
}
