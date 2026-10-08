package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Feature 006, US5, FR-002: a roomy limit on the whole API per origin, as a
 * safety net against a runaway client or bulk scraping. Health checks are
 * outside /api and never limited - the deployment platform polls them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["seguridad.limites.general=5/1m"])
class LimiteGeneralIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired lateinit var jwtService: JwtService

    private fun get(ruta: String, token: String? = null): Int =
        RestClient.create("http://localhost:$puerto").get().uri(ruta)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .exchange({ _, r -> r.statusCode.value() }, false)!!

    @Test
    fun `past the general quota any API route answers 429, authenticated or not, and health is never limited`() {
        val admin = jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)

        repeat(5) { assertEquals(200, get("/api/empleados", admin), "request ${it + 1}") }
        assertEquals(429, get("/api/empleados", admin))
        assertEquals(429, get("/api/materiales", admin), "the quota covers the whole API, not one route")

        repeat(20) { assertEquals(200, get("/actuator/health")) }
    }
}
