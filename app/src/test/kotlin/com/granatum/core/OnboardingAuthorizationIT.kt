package com.granatum.core

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
 * Feature 009, FR-010, SC-004: only an ADMIN onboards people; and feature 005's
 * routes for managing sign-up requests are gone (FR-003). In `app` because it
 * is authorisation, and `SecurityConfig` only exists here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OnboardingAuthorizationIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var jwtService: JwtService

    private val http by lazy { HttpTestClient(port) }

    private fun token(role: Role) = jwtService.generateAccessToken(UUID.randomUUID(), role)

    @Test
    fun `nobody but ADMIN may onboard a person`() {
        listOf(Role.MANAGER, Role.EMPLOYEE, Role.REPRESENTATIVE).forEach { role ->
            assertEquals(403, http.post("/api/auth/onboarding", "{}", token(role)).status, "$role onboarding")
        }
    }

    @Test
    fun `without a token onboarding is 401`() {
        assertEquals(401, http.post("/api/auth/onboarding", "{}").status)
    }

    @Test
    fun `ADMIN gets past the authorisation layer`() {
        val status = http.post("/api/auth/onboarding", "{}", token(Role.ADMIN)).status
        assertEquals(400, status, "an empty body is the validator's business, not the security chain's")
    }

    @Test
    fun `the sign-up request routes no longer exist`() {
        val admin = token(Role.ADMIN)
        // The exact URLs feature 005 served before spec 009 removed them. They
        // were never renamed to English, so they stay as they were: this test
        // guards that those exact addresses stay gone.
        val statuses = listOf(
            http.get("/api/auth/registros", admin).status,
            http.post("/api/auth/registros/${UUID.randomUUID()}/aprobar", "{}", admin).status,
            http.post("/api/auth/registros/${UUID.randomUUID()}/rechazar", "{}", admin).status
        )
        assertTrue(statuses.all { it == 404 || it == 403 }, statuses.toString())
    }
}
