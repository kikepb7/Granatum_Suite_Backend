package com.granatum.core

import com.granatum.core.api.controllers.ShiftController
import com.granatum.core.api.dto.ClockInRequest
import com.granatum.core.api.dto.ClockOutRequest
import com.granatum.core.domain.exception.ClientEventIdReusedException
import com.granatum.core.domain.exception.ClockSkewException
import com.granatum.core.domain.type.ContractType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * **The test the brief asked for by name**: resending an operation must not
 * duplicate it.
 *
 * Driven through the controller rather than the service, because idempotency
 * lives between the two: calling the service directly would bypass exactly the
 * layer under test.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class DuplicateResendIT {

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

    @Autowired lateinit var controller: ShiftController
    @Autowired lateinit var employeeRepository: EmployeeRepository
    @Autowired lateinit var dataSource: DataSource

    private lateinit var employee: EmployeeEntity

    @BeforeEach
    fun authenticate() {
        employee = employeeRepository.save(
            EmployeeEntity(
                name = "Persona de prueba",
                identityDocument = "T${UUID.randomUUID().toString().take(8).uppercase()}",
                position = "Florista",
                contractType = ContractType.FULL_TIME,
                hireDate = java.time.LocalDate.parse("2026-10-01"),
                active = true
            )
        )
        // The controller reads the subject from the security context, which is
        // what production does; the empleado id *is* the JWT subject.
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                employee.id, null, listOf(SimpleGrantedAuthority("ROLE_EMPLEADO"))
            )
    }

    @AfterEach
    fun clean() = SecurityContextHolder.clearContext()

    private fun countShifts(): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM shifts WHERE employee_id = ?"
            ).use { statement ->
                statement.setObject(1, employee.id)
                val rs = statement.executeQuery()
                rs.next()
                rs.getInt(1)
            }
        }

    /** SC-005. The same request, twice. */
    @Test
    fun `resending the same operation returns the same shift and creates only one`() {
        val clockInRequest = ClockInRequest(
            clientEventId = UUID.randomUUID(),
            occurredAt = Instant.now().minus(Duration.ofMinutes(30))
        )

        val firstResponse = controller.clockIn(clockInRequest)
        val secondResponse = controller.clockIn(clockInRequest)

        assertEquals(
            firstResponse.id,
            secondResponse.id,
            "the retry must answer with the original fichaje. A 409 SHIFT_ALREADY_IN_PROGRESS " +
                "here would mean idempotency is not implemented and the retry is being " +
                "treated as a fresh clock-in"
        )
        assertEquals(firstResponse, secondResponse, "the whole response must be the original one, verbatim")
        assertEquals(1, countShifts(), "exactly one row")
    }

    /**
     * The retry answers with what the **first** call returned, not with current
     * state.
     *
     * Asserted by letting the shift move on in between: the day is closed
     * between the two sends, so a handler that recomputed would answer CLOSED
     * with its worked minutes, while the stored response says IN_PROGRESS. This is
     * the case that makes "store the response" rather than "recompute it" the
     * right design, and the one a looser test would miss.
     */
    @Test
    fun `the retry returns the original response and not the current state`() {
        val clockIn = ClockInRequest(
            clientEventId = UUID.randomUUID(),
            occurredAt = Instant.now().minus(Duration.ofHours(8))
        )
        val firstResponse = controller.clockIn(clockIn)

        controller.clockOut(
            firstResponse.id,
            ClockOutRequest(clientEventId = UUID.randomUUID(), occurredAt = Instant.now())
        )

        val resend = controller.clockIn(clockIn)

        assertEquals(
            firstResponse,
            resend,
            "the shift closed in between, so recomputing would answer CLOSED with its " +
                "minutes; the stored response must still say what it said the first time"
        )
    }

    /** Same key, different body: a client bug, surfaced rather than absorbed. */
    @Test
    fun `the same key with a different body is a conflict`() {
        val key = UUID.randomUUID()
        val now = Instant.now().minus(Duration.ofMinutes(10))

        controller.clockIn(ClockInRequest(clientEventId = key, occurredAt = now))

        assertFailsWith<ClientEventIdReusedException> {
            controller.clockIn(
                ClockInRequest(
                    clientEventId = key,
                    occurredAt = now.plus(Duration.ofMinutes(5))
                )
            )
        }
    }

    /** FR-025: the shift carries the device's time, not the server's. */
    @Test
    fun `the shift is recorded with occurredAt and both instants are kept apart`() {
        val occurred = Instant.now().minus(Duration.ofHours(2))
        val shift = controller.clockIn(
            ClockInRequest(clientEventId = UUID.randomUUID(), occurredAt = occurred)
        )

        assertEquals(
            occurred.epochSecond,
            shift.clockIn.epochSecond,
            "the register must hold when it happened, not when it arrived"
        )

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT occurred_at, received_at FROM shift_events WHERE shift_id = ?"
            ).use { statement ->
                statement.setObject(1, shift.id)
                val rs = statement.executeQuery()
                rs.next()
                val occurredAt = rs.getTimestamp("occurred_at").toInstant()
                val receivedAt = rs.getTimestamp("received_at").toInstant()

                assertEquals(occurred.epochSecond, occurredAt.epochSecond)
                assertEquals(
                    true,
                    receivedAt.isAfter(occurredAt),
                    "arrival must be recorded separately and later, not conflated"
                )
            }
        }
    }

    @Test
    fun `an operation dated in the future is rejected`() {
        assertFailsWith<ClockSkewException> {
            controller.clockIn(
                ClockInRequest(
                    clientEventId = UUID.randomUUID(),
                    occurredAt = Instant.now().plus(Duration.ofMinutes(10))
                )
            )
        }
    }

    @Test
    fun `an operation from 48 hours ago is accepted`() {
        val shift = controller.clockIn(
            ClockInRequest(
                clientEventId = UUID.randomUUID(),
                occurredAt = Instant.now().minus(Duration.ofHours(48))
            )
        )
        assertEquals(1, countShifts())
        assertEquals(employee.id, shift.employeeId)
    }

    @Test
    fun `an operation from five days ago is rejected`() {
        assertFailsWith<ClockSkewException> {
            controller.clockIn(
                ClockInRequest(
                    clientEventId = UUID.randomUUID(),
                    occurredAt = Instant.now().minus(Duration.ofDays(5))
                )
            )
        }
    }

    /**
     * A rejected clock skew must leave no trace. Were the event stored, the bad
     * timestamp would become permanent and the key unusable for the corrected
     * retry - which is why the clock is validated before the idempotency
     * lookup, not after.
     */
    @Test
    fun `a clock-skew rejection records no event and leaves the key usable`() {
        val key = UUID.randomUUID()

        assertFailsWith<ClockSkewException> {
            controller.clockIn(
                ClockInRequest(clientEventId = key, occurredAt = Instant.now().plus(Duration.ofHours(1)))
            )
        }
        assertEquals(0, countShifts())

        // The same key now works with a plausible time.
        val shift = controller.clockIn(
            ClockInRequest(clientEventId = key, occurredAt = Instant.now().minus(Duration.ofMinutes(5)))
        )
        assertEquals(1, countShifts())
        assertEquals(employee.id, shift.employeeId)
    }
}
