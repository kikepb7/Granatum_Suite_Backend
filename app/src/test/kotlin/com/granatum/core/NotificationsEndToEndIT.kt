package com.granatum.core

import com.granatum.core.HttpTestClient.Companion.field
import com.granatum.core.HttpTestClient.Companion.validDni
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Feature 008 end to end, with every real piece: `absences` publishes,
 * `notifications` routes through `auth`'s RoleDirectory, and the JSON is the
 * one the server actually writes (SC-001, SC-004).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationsEndToEndIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var jwtService: JwtService

    private val http by lazy { HttpTestClient(port) }
    private val admin by lazy { jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN) }

    private fun person(): UUID {
        val created = http.post(
            "/api/employees",
            """{"name":"Persona Avisos","identityDocument":"${validDni()}","position":"Florista","contractType":"PART_TIME","hireDate":"2026-01-01"}""",
            admin
        )
        assertEquals(201, created.status, created.body)
        return UUID.fromString(field(created.body, "id"))
    }

    private fun withAccount(employeeId: UUID, role: Role) {
        val account = http.post(
            "/api/auth/accounts",
            """{"employeeId":"$employeeId","email":"p-${UUID.randomUUID()}@granatum.es","role":"$role"}""",
            admin
        )
        assertEquals(201, account.status, account.body)
    }

    @Test
    fun `an absence request reaches the MANAGER and its approval reaches the person`() {
        val employeeId = person()
        val managerId = person().also { withAccount(it, Role.MANAGER) }
        val employee = jwtService.generateAccessToken(employeeId, Role.EMPLOYEE)
        val manager = jwtService.generateAccessToken(managerId, Role.MANAGER)
        val year = LocalDate.now().year + 1

        val requested = http.post("/api/absences", """{"type":"VACATION","from":"$year-04-06","to":"$year-04-08"}""", employee)
        assertEquals(201, requested.status, requested.body)
        val absenceId = assertNotNull(field(requested.body, "id"))

        val managerInbox = http.get("/api/notifications?unreadOnly=true", manager)
        assertEquals(200, managerInbox.status)
        assertTrue(
            managerInbox.body.contains("\"type\":\"ABSENCE_PENDING\",\"referenceId\":\"$absenceId\""),
            managerInbox.body
        )
        assertTrue(managerInbox.body.contains("\"readAt\":null"), "the unread mark travels as an explicit null")

        assertEquals(200, http.post("/api/absences/$absenceId/approve", "{}", manager).status)

        val personInbox = http.get("/api/notifications", employee)
        assertTrue(
            personInbox.body.contains("\"type\":\"ABSENCE_APPROVED\",\"referenceId\":\"$absenceId\""),
            personInbox.body
        )
        assertEquals(false, personInbox.body.contains("ABSENCE_PENDING"), "nobody is told about their own request")
    }

    @Test
    fun `nobody marks someone else's notice, and the inbox needs a token`() {
        val employeeId = person()
        val managerId = person().also { withAccount(it, Role.MANAGER) }
        val year = LocalDate.now().year + 1
        http.post(
            "/api/absences", """{"type":"VACATION","from":"$year-05-04","to":"$year-05-05"}""",
            jwtService.generateAccessToken(employeeId, Role.EMPLOYEE)
        )
        val manager = jwtService.generateAccessToken(managerId, Role.MANAGER)
        val noticeId = assertNotNull(field(http.get("/api/notifications", manager).body, "id"))

        val intruder = jwtService.generateAccessToken(UUID.randomUUID(), Role.EMPLOYEE)
        assertEquals(404, http.post("/api/notifications/$noticeId/read", "{}", intruder).status)
        assertEquals(401, http.get("/api/notifications").status)
        assertEquals(200, http.get("/api/notifications", jwtService.generateAccessToken(UUID.randomUUID(), Role.REPRESENTATIVE)).status)
        assertEquals(204, http.post("/api/notifications/$noticeId/read", "{}", manager).status)
    }
}
