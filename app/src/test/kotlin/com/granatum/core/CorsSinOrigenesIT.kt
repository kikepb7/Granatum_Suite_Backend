package com.granatum.core

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals

/**
 * Feature 006, FR-012: with no origin configured - the default - no browser
 * origin is admitted. The mobile app sends no Origin and is not affected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsSinOrigenesIT {

    @LocalServerPort
    var puerto: Int = 0

    @Test
    fun `with no configured origin every preflight is refused`() {
        val estado = RestClient.create("http://localhost:$puerto").method(HttpMethod.OPTIONS).uri("/api/auth/login")
            .header("Origin", "https://app.granatum.es")
            .header("Access-Control-Request-Method", "POST")
            .exchange({ _, r -> r.statusCode.value() }, false)!!

        assertEquals(403, estado)
    }

    @Test
    fun `a request without Origin, like the mobile app's, is unaffected`() {
        val estado = RestClient.create("http://localhost:$puerto").get().uri("/actuator/health")
            .exchange({ _, r -> r.statusCode.value() }, false)!!

        assertEquals(200, estado)
    }
}
