package com.granatum.core

import com.granatum.core.domain.model.ShiftValues
import com.granatum.core.domain.model.BreakValues
import com.granatum.core.domain.type.CorrectionStatus
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.ContractType
import com.granatum.core.domain.type.ShiftOperationType
import com.granatum.core.domain.type.BreakType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.service.ShiftCorrectionService
import com.granatum.core.service.ShiftService
import com.granatum.core.service.ShiftEventRecorder
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The derivability invariant constitution principle III requires since
 * v1.1.0**: a fichaje's state must at all times be derivable from the event log
 * plus its approved corrections.
 *
 * The reason it is worth a test of its own is that it catches a class of bug no
 * other test here can. Every other assertion checks that a specific operation
 * behaves correctly; this one checks that **no value got into the projection by
 * some other route**. If a field in `shifts` is explained neither by an event
 * nor by an approved correction, it was written through a path that should not
 * exist - and that is exactly what tampering would look like.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class StateDerivabilityIT {

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
        }
    }

    @Autowired lateinit var shiftService: ShiftService
    @Autowired lateinit var correctionService: ShiftCorrectionService
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var recorder: ShiftEventRecorder
    @Autowired lateinit var dataSource: DataSource

    private val madrid = com.granatum.core.api.util.CivilDateRange.ZONE
    private val manager = UUID.randomUUID()

    private fun madrid(date: String, time: String) =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(madrid).toInstant()

    private fun newEmployee(): EmployeeEntity =
        employeeRepository.save(
            EmployeeEntity(
                name = "Persona de prueba",
                identityDocument = "T${UUID.randomUUID().toString().take(8).uppercase()}",
                position = "Florista",
                contractType = ContractType.FULL_TIME,
                hireDate = LocalDate.parse("2026-10-01"),
                active = true
            )
        )

    /** Reconstructs the shift's boundaries from the event log alone. */
    private fun deriveFromEvents(shiftId: UUID): Pair<Instant?, Instant?> {
        var clockIn: Instant? = null
        var clockOut: Instant? = null

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT operation_type, occurred_at
                  FROM shift_events
                 WHERE shift_id = ?
                 ORDER BY occurred_at
                """.trimIndent()
            ).use { statement ->
                statement.setObject(1, shiftId)
                val rs = statement.executeQuery()
                while (rs.next()) {
                    val type = ShiftOperationType.valueOf(rs.getString("operation_type"))
                    val whenAt = rs.getTimestamp("occurred_at").toInstant()
                    when (type) {
                        ShiftOperationType.CLOCK_IN -> clockIn = whenAt
                        ShiftOperationType.CLOCK_OUT -> clockOut = whenAt
                        else -> Unit
                    }
                }
            }
        }

        return clockIn to clockOut
    }

    private fun recordEvent(
        employeeId: UUID,
        shiftId: UUID,
        type: ShiftOperationType,
        occurredAt: Instant
    ) {
        recorder.record(
            clientEventId = UUID.randomUUID(),
            employeeId = employeeId,
            shiftId = shiftId,
            operationType = type,
            occurredAt = occurredAt,
            requestBody = """{"occurredAt":"$occurredAt"}""",
            responseStatus = 200,
            responseBody = """{"id":"$shiftId"}"""
        )
    }

    @Test
    fun `a shift's state is derivable from its events alone`() {
        val employee = newEmployee()
        val clockIn = madrid("2026-10-05", "07:00")
        val clockOut = madrid("2026-10-05", "16:00")

        var shift = shiftService.clockIn(employee.id, clockIn, null)
        recordEvent(employee.id, shift.id, ShiftOperationType.CLOCK_IN, clockIn)

        shift = shiftService.clockOut(shift.id, employee.id, clockOut, null)
        recordEvent(employee.id, shift.id, ShiftOperationType.CLOCK_OUT, clockOut)

        val (derivedClockIn, derivedClockOut) = deriveFromEvents(shift.id)
        val projection = shiftService.findById(shift.id)

        assertEquals(
            derivedClockIn,
            projection.clockIn,
            "the projected entry must be explained by a CLOCK_IN event"
        )
        assertEquals(
            derivedClockOut,
            projection.clockOut,
            "the projected exit must be explained by a CLOCK_OUT event"
        )
    }

    /**
     * After a correction, the projection no longer matches the raw events - and
     * that is correct. What the invariant requires is that the difference is
     * explained by an **approved** correction, and that the pre-correction value
     * is still recoverable from it.
     */
    @Test
    fun `where the projection diverges from the events, an approved correction explains it`() {
        val employee = newEmployee()
        val clockIn = madrid("2026-10-05", "07:00")
        val clockedOut = madrid("2026-10-05", "16:00")
        val correctedClockOut = madrid("2026-10-05", "15:00")

        var shift = shiftService.clockIn(employee.id, clockIn, null)
        recordEvent(employee.id, shift.id, ShiftOperationType.CLOCK_IN, clockIn)
        shift = shiftService.clockOut(shift.id, employee.id, clockedOut, null)
        recordEvent(employee.id, shift.id, ShiftOperationType.CLOCK_OUT, clockedOut)

        val correctionRequest = correctionService.request(
            shift.id, employee.id, Role.EMPLOYEE, "Me marche una hora antes",
            ShiftValues(clockIn, correctedClockOut, emptyList())
        )
        correctionService.approve(correctionRequest.id, manager, Role.MANAGER)

        val projection = shiftService.findById(shift.id)
        val (_, derivedClockOut) = deriveFromEvents(shift.id)

        // The projection diverges from the log...
        assertEquals(correctedClockOut, projection.clockOut)
        assertEquals(clockedOut, derivedClockOut)
        assertTrue(projection.clockOut != derivedClockOut)

        // ...and an approved correction accounts for the difference, carrying
        // the value the events recorded.
        val corrections = correctionService.findByShift(shift.id)
        val approved = corrections.filter { it.status == CorrectionStatus.APPROVED }
        assertEquals(1, approved.size, "exactly one approved correction must explain the divergence")

        val originals = approved.single().originalValues
        assertEquals(
            derivedClockOut,
            originals?.clockOut,
            "the correction's snapshot must hold the value the event log recorded, " +
                "which is what keeps the chain from events to current state unbroken"
        )
    }

    /**
     * A tampered projection is detectable.
     *
     * Writes directly to the table - the one route the application does not
     * offer - and shows the invariant notices. This is the failure mode the
     * invariant exists for: not a bug in an operation, but a value that no
     * operation explains.
     */
    @Test
    fun `a value written outside the event log is detectable as unexplained`() {
        val employee = newEmployee()
        val clockIn = madrid("2026-10-05", "07:00")
        val clockOut = madrid("2026-10-05", "16:00")

        var shift = shiftService.clockIn(employee.id, clockIn, null)
        recordEvent(employee.id, shift.id, ShiftOperationType.CLOCK_IN, clockIn)
        shift = shiftService.clockOut(shift.id, employee.id, clockOut, null)
        recordEvent(employee.id, shift.id, ShiftOperationType.CLOCK_OUT, clockOut)

        // Straight UPDATE, bypassing everything.
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "UPDATE shifts SET clock_out = clock_out + interval '2 hours' " +
                        "WHERE id = '${shift.id}'"
                )
            }
        }

        val projection = shiftService.findById(shift.id)
        val (_, derivedClockOut) = deriveFromEvents(shift.id)
        val approved = correctionService.findByShift(shift.id)
            .filter { it.status == CorrectionStatus.APPROVED }

        assertTrue(
            projection.clockOut != derivedClockOut && approved.isEmpty(),
            "the projection diverges from the event log with no approved correction " +
                "to explain it - which is precisely the signature of tampering, and " +
                "what this invariant is for"
        )
    }

    @Test
    fun `breaks are reflected in the projection and the events agree on their count`() {
        val employee = newEmployee()
        val clockIn = madrid("2026-10-05", "07:00")

        var shift = shiftService.clockIn(employee.id, clockIn, null)
        recordEvent(employee.id, shift.id, ShiftOperationType.CLOCK_IN, clockIn)

        val breakStart = madrid("2026-10-05", "11:00")
        shift = shiftService.startBreak(shift.id, employee.id, breakStart, BreakType.MEAL)
        recordEvent(employee.id, shift.id, ShiftOperationType.BREAK_START, breakStart)

        val breakEnd = madrid("2026-10-05", "11:45")
        shift = shiftService.endBreak(shift.id, employee.id, breakEnd)
        recordEvent(employee.id, shift.id, ShiftOperationType.BREAK_END, breakEnd)

        val clockOut = madrid("2026-10-05", "16:00")
        shift = shiftService.clockOut(shift.id, employee.id, clockOut, null)
        recordEvent(employee.id, shift.id, ShiftOperationType.CLOCK_OUT, clockOut)

        val projection = shiftService.findById(shift.id)
        assertEquals(1, projection.breaks.size)
        assertEquals(breakStart, projection.breaks.single().start)
        assertEquals(breakEnd, projection.breaks.single().end)
        assertEquals(495, projection.workedMinutes)

        // One BREAK_START and one BREAK_END in the log account for the single
        // break in the projection.
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM shift_events WHERE shift_id = ? " +
                    "AND operation_type IN ('BREAK_START','BREAK_END')"
            ).use { statement ->
                statement.setObject(1, shift.id)
                val rs = statement.executeQuery()
                rs.next()
                assertEquals(2, rs.getInt(1))
            }
        }
    }
}
