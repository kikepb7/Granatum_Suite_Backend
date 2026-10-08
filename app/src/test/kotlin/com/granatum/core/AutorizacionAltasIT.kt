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
class AutorizacionAltasIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired lateinit var jwtService: JwtService

    private val http by lazy { ClientePruebaHttp(puerto) }

    private fun token(rol: Role) = jwtService.generateAccessToken(UUID.randomUUID(), rol)

    @Test
    fun `nobody but ADMIN may onboard a person`() {
        listOf(Role.ENCARGADO, Role.EMPLEADO, Role.REPRESENTANTE).forEach { rol ->
            assertEquals(403, http.post("/api/auth/altas", "{}", token(rol)).estado, "$rol onboarding")
        }
    }

    @Test
    fun `without a token onboarding is 401`() {
        assertEquals(401, http.post("/api/auth/altas", "{}").estado)
    }

    @Test
    fun `ADMIN gets past the authorisation layer`() {
        val estado = http.post("/api/auth/altas", "{}", token(Role.ADMIN)).estado
        assertEquals(400, estado, "an empty body is the validator's business, not the security chain's")
    }

    @Test
    fun `the sign-up request routes no longer exist`() {
        val admin = token(Role.ADMIN)
        val estados = listOf(
            http.get("/api/auth/registros", admin).estado,
            http.post("/api/auth/registros/${UUID.randomUUID()}/aprobar", "{}", admin).estado,
            http.post("/api/auth/registros/${UUID.randomUUID()}/rechazar", "{}", admin).estado
        )
        assertTrue(estados.all { it == 404 || it == 403 }, estados.toString())
    }
}
