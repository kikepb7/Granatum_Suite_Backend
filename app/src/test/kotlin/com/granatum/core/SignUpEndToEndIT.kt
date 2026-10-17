package com.granatum.core

import com.granatum.core.HttpTestClient.Companion.field
import com.granatum.core.HttpTestClient.Companion.validDni
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sign-up of the owner (feature 005 US1) and onboarding by an ADMIN (feature
 * 009) end to end, with the real `timetracking` behind the `StaffRecords`
 * contract and the real `SecurityConfig`.
 *
 * ## The one piece of setup that touches other tests' data
 *
 * The bootstrap only works while no ADMIN account exists, and this database is
 * shared with the other classes in `app`. So the bootstrap test demotes the
 * existing ADMIN accounts first and **restores them** afterwards, by id - never
 * deleting anything, since accounts has no delete path anywhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["auth.sign-up.bootstrap-code=${SignUpEndToEndIT.CODE}"])
class SignUpEndToEndIT {

    companion object {
        const val CODE = "bootstrap-end-to-end-0123456789"
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate

    private val http by lazy { HttpTestClient(port) }
    private val password = "Granatum-Punta-2026!"

    private fun newEmail() = "p-${UUID.randomUUID()}@granatum.es"

    private fun signUp(email: String, document: String, bootstrapCode: String? = null) =
        http.post(
            "/api/auth/sign-up",
            """{"email":"$email","password":"$password","name":"Persona de prueba","identityDocument":"$document"""" +
                (bootstrapCode?.let { ""","bootstrapCode":"$it"""" } ?: "") + "}"
        )

    private fun login(email: String) =
        http.post("/api/auth/login", """{"email":"$email","password":"$password"}""")

    @Test
    fun `the first ADMIN signs up with the bootstrap code and manages staff straight away`() {
        val admins = jdbc.queryForList("SELECT id FROM accounts WHERE role = 'ADMIN'", UUID::class.java)
        jdbc.update("UPDATE accounts SET role = 'EMPLOYEE' WHERE role = 'ADMIN'")
        try {
            val email = newEmail()

            val created = signUp(email, validDni(), CODE)
            assertEquals(201, created.status, created.body)

            val session = login(email)
            assertEquals(200, session.status, session.body)
            val token = assertNotNull(field(session.body, "accessToken"))
            assertEquals(200, http.get("/api/employees", token).status, "an ADMIN manages staff")

            assertEquals(403, signUp(newEmail(), validDni(), CODE).status, "the code is spent")
        } finally {
            if (admins.isNotEmpty()) {
                jdbc.update(
                    "UPDATE accounts SET role = 'ADMIN' WHERE id IN (${admins.joinToString { "'$it'" }})"
                )
            }
        }
    }

    /** Feature 009, FR-001: only the owner signs up; without the code nothing is stored. */
    @Test
    fun `signing up without the bootstrap code is refused and stores nothing`() {
        val email = newEmail()

        val response = signUp(email, validDni())

        assertEquals(400, response.status, response.body)
        assertTrue(response.body.contains("\"code\":\"VALIDATION\""), response.body)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM accounts WHERE email = ?", Int::class.java, email))
    }

    /**
     * Feature 009, US2 end to end: the ADMIN onboards a person in one call with
     * the real timetracking behind StaffRecords, hands over the temporary
     * password, and the person can do nothing but change it - then clocks in.
     */
    @Test
    fun `an ADMIN onboards a worker, who changes the temporary password and clocks in`() {
        val admin = jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)
        val email = newEmail()

        val onboarding = http.post(
            "/api/auth/onboarding",
            """{"name":"Persona de prueba","identityDocument":"${validDni()}","position":"Florista","contractType":"PART_TIME","hireDate":"2026-10-01","email":"$email","role":"EMPLOYEE"}""",
            admin
        )
        assertEquals(201, onboarding.status, onboarding.body)
        val temporary = assertNotNull(field(onboarding.body, "temporaryPassword"))
        val employeeId = assertNotNull(field(onboarding.body, "employeeId"))
        assertEquals("Florista", field(http.get("/api/employees/$employeeId", admin).body, "position"))

        val first = http.post("/api/auth/login", """{"email":"$email","password":"$temporary"}""")
        assertEquals(200, first.status, first.body)
        assertTrue(first.body.contains("\"passwordChangeRequired\":true"), first.body)
        val provisional = assertNotNull(field(first.body, "accessToken"))
        val clockIn = """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${HttpTestClient.minutesAgo(5)}"}"""
        assertEquals(403, http.post("/api/shifts/clock-in", clockIn, provisional).status, "only the password change until it is done")

        val change = http.post(
            "/api/auth/change-password",
            """{"currentPassword":"$temporary","newPassword":"$password"}""",
            provisional
        )
        assertEquals(200, change.status, change.body)
        val definitive = assertNotNull(field(change.body, "accessToken"))

        assertEquals(201, http.post("/api/shifts/clock-in", clockIn, definitive).status)
        assertEquals(200, login(email).status, "and the person's own password works from now on")
    }
}
