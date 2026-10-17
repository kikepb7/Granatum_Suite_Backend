package com.granatum.core

import com.granatum.core.HttpTestClient.Companion.field
import com.granatum.core.HttpTestClient.Companion.minutesAgo
import com.granatum.core.HttpTestClient.Companion.validDni
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * **SC-001 - the criterion that gives this feature its reason to exist.**
 *
 * A person is registered, given access, changes their temporary password,
 * signs in and completes a working day - and the fichaje is attributed to them
 * **without the client ever sending an identifier**. Before this feature the
 * identity behind every fichaje came from `/api/dev/token`, which issues any
 * role to anyone, so no record in the working-time register was reliably
 * attributable to a person.
 *
 * ## Why it can only live in `app`
 *
 * It needs `auth`, `timetracking` and the real `SecurityConfig` together, and
 * `app` is the only module that has all three.
 *
 * ## The one shortcut, and why it is not the one SC-001 forbids
 *
 * The `ADMIN` who registers the person is minted straight from `JwtService`.
 * Somebody has to be the first administrator, and that is a bootstrap concern,
 * not the subject of this test. What SC-001 rules out is the **employee**
 * reaching the register through the development emitter, and every step the
 * employee takes here goes through the real sign-in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginAndClockInIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val http by lazy { HttpTestClient(port) }

    private val admin by lazy { jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN) }

    @Test
    fun `a person signs in for real and completes a working day`() {
        // 1. Registered as staff (timetracking).
        val employee = http.post(
            "/api/employees",
            """{"name":"Persona SC-001","identityDocument":"${validDni()}",
                "position":"Florista","contractType":"FULL_TIME","hireDate":"2026-10-01"}""",
            admin
        )
        assertEquals(201, employee.status, employee.body)
        val employeeId = field(employee.body, "id")!!

        // 2. Given access, in one operation (auth).
        val email = "sc001-${UUID.randomUUID()}@granatum.es"
        val grant = http.post(
            "/api/auth/accounts",
            """{"employeeId":"$employeeId","email":"$email"}""",
            admin
        )
        assertEquals(201, grant.status, grant.body)
        val temporaryPassword = field(grant.body, "temporaryPassword")!!

        // 3. First sign-in: pending change.
        val first = http.post("/api/auth/login", """{"email":"$email","password":"$temporaryPassword"}""")
        assertEquals(200, first.status, first.body)
        assertEquals("true", field(first.body, "passwordChangeRequired"))

        // 4. Changes the temporary password.
        val change = http.post(
            "/api/auth/change-password",
            """{"currentPassword":"$temporaryPassword","newPassword":"Granatum-2026!"}""",
            field(first.body, "accessToken")
        )
        assertEquals(200, change.status, change.body)
        val accessToken = field(change.body, "accessToken")!!

        // 5. Clocks in. No employee id anywhere in the request.
        val clockIn = http.post(
            "/api/shifts/clock-in",
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${minutesAgo(90)}"}""",
            accessToken
        )
        assertEquals(201, clockIn.status, clockIn.body)
        assertEquals(
            employeeId,
            field(clockIn.body, "employeeId"),
            "the shift must belong to the person who signed in, derived from the token " +
                "subject alone - that is what makes the register attributable"
        )
        val shiftId = assertNotNull(field(clockIn.body, "id"))

        // 6. Clocks out.
        val clockOut = http.post(
            "/api/shifts/$shiftId/clock-out",
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${minutesAgo(1)}"}""",
            accessToken
        )
        assertEquals(200, clockOut.status, clockOut.body)
        assertEquals("CLOSED", field(clockOut.body, "status"))
    }
}
