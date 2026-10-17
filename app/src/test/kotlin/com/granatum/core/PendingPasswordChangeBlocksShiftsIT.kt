package com.granatum.core

import com.granatum.core.HttpTestClient.Companion.minutesAgo
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **SC-008**: a session pending a password change can do nothing but change it.
 *
 * Tested against the routes of *another* feature on purpose. `timetracking`
 * knows nothing about `auth`; the refusal has to come entirely from the
 * restricted authority the filter in `common` grants instead of the role, and
 * from rules in `SecurityConfig` that are all written as `hasAnyRole(...)`.
 * If it works here, it works for every feature without any of them changing.
 *
 * Only in `app`: in `auth` there is no global filter chain, so this test would
 * pass there without exercising any authorisation at all (D-016).
 *
 * The token is minted directly with the claim. What is under test is the
 * authorisation of a pending token, not how one is obtained - `auth` covers
 * that.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PendingPasswordChangeBlocksShiftsIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val http by lazy { HttpTestClient(port) }

    private fun pending(role: Role) =
        jwtService.generateAccessToken(UUID.randomUUID(), role, requiresPasswordChange = true)

    @Test
    fun `a pending token cannot clock in`() {
        val r = http.post(
            "/api/shifts/clock-in",
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${minutesAgo(5)}"}""",
            pending(Role.EMPLOYEE)
        )
        assertEquals(403, r.status, "SC-008: the only operation allowed is changing the password")
    }

    @Test
    fun `a pending token cannot read the register either`() {
        assertEquals(403, http.get("/api/shifts", pending(Role.EMPLOYEE)).status)
    }

    /**
     * The state of the session beats the role of the person: an ADMIN with a
     * temporary password is exactly as restricted as anybody else, or the
     * temporary password an administrator was handed by another administrator
     * would be a permanent all-access credential.
     */
    @Test
    fun `a pending ADMIN is restricted like anybody else`() {
        val token = pending(Role.ADMIN)

        assertEquals(403, http.get("/api/materials", token).status)
        assertEquals(403, http.get("/api/employees", token).status)
        assertEquals(
            403,
            http.post("/api/auth/accounts", """{"employeeId":"${UUID.randomUUID()}","email":"x@granatum.es"}""", token).status,
            "a pending ADMIN must not be able to hand out access"
        )
    }

    /**
     * The other half. A pending token must reach `change-password`, or the
     * person is locked out for good. The body is deliberately invalid: getting
     * past the authorisation layer is the assertion, and anything other than
     * 401/403 proves it.
     */
    @Test
    fun `a pending token does reach change-password`() {
        val r = http.post("/api/auth/change-password", "{}", pending(Role.EMPLOYEE))

        assertTrue(r.status !in setOf(401, 403), "got ${r.status}: ${r.body}")
    }
}
