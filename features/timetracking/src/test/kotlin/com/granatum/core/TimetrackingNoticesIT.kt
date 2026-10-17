package com.granatum.core

import com.granatum.core.domain.event.DomainNotice
import com.granatum.core.domain.event.NoticeType
import com.granatum.core.domain.model.ShiftValues
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.ContractType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.scheduling.MissingClockOutNoticeJob
import com.granatum.core.scheduling.IncompleteShiftMarkingJob
import com.granatum.core.service.ShiftCorrectionService
import com.granatum.core.service.ShiftService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What this module tells the notices module (feature 008): FR-001 to FR-003,
 * FR-005. Only the publication is under test - who receives what is
 * `notifications`' business, tested there.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
@RecordApplicationEvents
class TimetrackingNoticesIT {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("timetracking.incomplete.cron") { "0 0 4 1 1 *" }
            registry.add("timetracking.retention.cron") { "0 0 4 1 1 *" }
            registry.add("timetracking.missing-clock-out.cron") { "0 0 4 1 1 *" }
        }
    }

    @Autowired lateinit var events: ApplicationEvents
    @Autowired lateinit var shifts: ShiftService
    @Autowired lateinit var corrections: ShiftCorrectionService
    @Autowired lateinit var employees: EmployeeRepository
    @Autowired lateinit var forgottenClockOut: MissingClockOutNoticeJob
    @Autowired lateinit var incompletes: IncompleteShiftMarkingJob

    private fun person(): EmployeeEntity = employees.save(
        EmployeeEntity(
            name = "Persona de prueba",
            identityDocument = "T${UUID.randomUUID().toString().take(8).uppercase()}",
            position = "Florista",
            contractType = ContractType.FULL_TIME,
            hireDate = LocalDate.parse("2026-01-01"),
            active = true
        )
    )

    private fun notices(type: NoticeType) = events.stream(DomainNotice::class.java).filter { it.type == type }.toList()

    private fun hoursAgo(h: Long): Instant = Instant.now().minus(h, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)

    @Test
    fun `a shift open for more than the threshold is announced to its owner, a recent one is not`() {
        val forgetful = person()
        val recent = person()
        val old = shifts.clockIn(forgetful.id, hoursAgo(11), null)
        shifts.clockIn(recent.id, hoursAgo(2), null)

        forgottenClockOut.sendNotices()

        val withoutClockOut = notices(NoticeType.SHIFT_WITHOUT_CLOCK_OUT)
        assertTrue(withoutClockOut.any { it.referenceId == old.id && it.ownerId == forgetful.id })
        assertTrue(withoutClockOut.none { it.ownerId == recent.id })
    }

    @Test
    fun `a shift marked incomplete is announced to its owner`() {
        val p = person()
        val yesterday = shifts.clockIn(p.id, hoursAgo(30), null)

        incompletes.markIncomplete()

        assertTrue(notices(NoticeType.SHIFT_INCOMPLETE).any { it.referenceId == yesterday.id && it.ownerId == p.id })
    }

    @Test
    fun `a correction request and its resolution are announced`() {
        val p = person()
        val clockIn = hoursAgo(9)
        val f = shifts.clockIn(p.id, clockIn, null)
        shifts.clockOut(f.id, p.id, hoursAgo(1), null)
        val manager = UUID.randomUUID()

        val requested = corrections.request(f.id, p.id, Role.EMPLOYEE, "Olvidé una pausa", ShiftValues(clockIn, hoursAgo(2), emptyList()))
        val pending = notices(NoticeType.CORRECTION_PENDING).single { it.referenceId == requested.id }
        assertEquals(p.id, pending.authorId, "so the requester is not told about their own request")
        assertEquals(p.id, pending.ownerId)

        corrections.approve(requested.id, manager, Role.MANAGER)
        val approvedRequest = notices(NoticeType.CORRECTION_APPROVED).single { it.referenceId == requested.id }
        assertEquals(p.id, approvedRequest.ownerId)
    }

    @Test
    fun `a rejected correction is announced to the shift's owner, whoever asked`() {
        val p = person()
        val clockIn = hoursAgo(9)
        val f = shifts.clockIn(p.id, clockIn, null)
        shifts.clockOut(f.id, p.id, hoursAgo(1), null)
        val manager = UUID.randomUUID()
        val requested = corrections.request(f.id, manager, Role.MANAGER, "Ajuste", ShiftValues(clockIn, hoursAgo(2), emptyList()))

        corrections.reject(requested.id, UUID.randomUUID(), Role.ADMIN, "No procede")

        assertEquals(p.id, notices(NoticeType.CORRECTION_REJECTED).single { it.referenceId == requested.id }.ownerId)
    }
}
