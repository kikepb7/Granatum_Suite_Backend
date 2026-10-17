package com.granatum.core

import com.granatum.core.api.util.CivilDateRange
import com.granatum.core.domain.service.WorkedTimeCalculator
import com.granatum.core.domain.type.ShiftStatus
import com.granatum.core.domain.type.ContractType
import com.granatum.core.domain.type.BreakType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * Seeds the register with fixed dates for the export tests.
 *
 * Through JDBC for the reason [RetentionIT] gives: the service refuses
 * timestamps outside the clock tolerance, and an export test needs past
 * months, daylight-saving days and shifts years old. Worked minutes come from
 * [WorkedTimeCalculator], the same function the service uses, so a seeded shift
 * is one the service could have produced.
 *
 * Times are civil times in `Europe/Madrid`, as the tests read them.
 */
class RegisterSeed(
    private val dataSource: DataSource,
    private val employees: EmployeeRepository
) {
    data class SeedBreak(val type: BreakType, val start: String, val end: String?)

    fun employee(
        name: String = "Persona de prueba",
        type: ContractType = ContractType.FULL_TIME,
        active: Boolean = true,
        document: String = "T${UUID.randomUUID().toString().take(8).uppercase()}"
    ): EmployeeEntity =
        employees.save(
            EmployeeEntity(
                name = name,
                identityDocument = document,
                position = "Florista",
                contractType = type,
                hireDate = LocalDate.parse("2020-01-01"),
                active = active
            )
        )

    fun instantOf(dateTime: String): Instant =
        LocalDateTime.parse(dateTime.replace(' ', 'T')).atZone(CivilDateRange.ZONE).toInstant()

    /**
     * A shift. [clockOut] null leaves it open (`IN_PROGRESS`) unless [status] says
     * otherwise; minutes are only computed for a closed one.
     */
    fun shift(
        employee: EmployeeEntity,
        clockIn: String,
        clockOut: String?,
        breaks: List<SeedBreak> = emptyList(),
        status: ShiftStatus = if (clockOut == null) ShiftStatus.IN_PROGRESS else ShiftStatus.CLOSED,
        wasIncomplete: Boolean = false,
        location: Pair<String, String>? = null
    ): UUID {
        val id = UUID.randomUUID()
        val minutes = if (status == ShiftStatus.CLOSED && clockOut != null) {
            WorkedTimeCalculator.workedMinutes(
                instantOf(clockIn),
                instantOf(clockOut),
                breaks.map { WorkedTimeCalculator.BreakInterval(instantOf(it.start), instantOf(it.end!!)) }
            )
        } else {
            null
        }
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO shifts
                    (id, employee_id, clock_in, clock_out, status, worked_minutes, was_incomplete,
                     clock_in_latitude, clock_in_longitude, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::numeric, ?::numeric, now(), now())
                """.trimIndent()
            ).use { s ->
                s.setObject(1, id)
                s.setObject(2, employee.id)
                s.setTimestamp(3, Timestamp.from(instantOf(clockIn)))
                s.setTimestamp(4, clockOut?.let { Timestamp.from(instantOf(it)) })
                s.setString(5, status.name)
                s.setObject(6, minutes)
                s.setBoolean(7, wasIncomplete)
                s.setString(8, location?.first)
                s.setString(9, location?.second)
                s.executeUpdate()
            }
            breaks.forEach { p ->
                c.prepareStatement(
                    "INSERT INTO shift_breaks (id, shift_id, type, started_at, ended_at) VALUES (?, ?, ?, ?, ?)"
                ).use { s ->
                    s.setObject(1, UUID.randomUUID())
                    s.setObject(2, id)
                    s.setString(3, p.type.name)
                    s.setTimestamp(4, Timestamp.from(instantOf(p.start)))
                    s.setTimestamp(5, p.end?.let { Timestamp.from(instantOf(it)) })
                    s.executeUpdate()
                }
            }
        }
        return id
    }

    /**
     * Every row and every `updated_at` of the register, so a test can show that
     * exporting changed nothing (FR-018).
     */
    fun registerFingerprint(): Map<String, String> =
        dataSource.connection.use { c ->
            listOf(
                "employees" to "SELECT count(*), max(updated_at) FROM employees",
                "shifts" to "SELECT count(*), max(updated_at) FROM shifts",
                "breaks" to "SELECT count(*), max(coalesce(ended_at, started_at)) FROM shift_breaks",
                "requests" to "SELECT count(*), max(coalesce(resolved_at, created_at)) FROM shift_correction_requests",
                "events" to "SELECT count(*), max(received_at) FROM shift_events"
            ).associate { (table, sql) ->
                c.createStatement().use { s ->
                    s.executeQuery(sql).use { r ->
                        r.next()
                        table to "${r.getLong(1)}/${r.getString(2)}"
                    }
                }
            }
        }
}
