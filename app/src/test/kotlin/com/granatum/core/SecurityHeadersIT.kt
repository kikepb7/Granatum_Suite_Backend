package com.granatum.core

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals

/**
 * Feature 006, FR-011, SC-005: every response defends itself in a browser - on
 * success, on a 401 and on a 404 alike.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityHeadersIT {

    @LocalServerPort
    var port: Int = 0

    private fun headers(path: String): Pair<Int, HttpHeaders> =
        RestClient.create("http://localhost:$port").get().uri(path)
            .exchange({ _, r -> r.statusCode.value() to r.headers }, false)!!

    private val expected = mapOf(
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Content-Security-Policy" to "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        "Referrer-Policy" to "no-referrer",
        "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), payment=()"
    )

    private fun check(path: String, expectedStatus: Int) {
        val (status, h) = headers(path)
        assertEquals(expectedStatus, status, path)
        expected.forEach { (name, value) -> assertEquals(value, h.getFirst(name), "$name on $path ($status)") }
        assertEquals(true, h.getFirst("Cache-Control")?.contains("no-store"), "Cache-Control on $path")
    }

    @Test
    fun `a successful response carries the security headers`() = check("/actuator/health", 200)

    @Test
    fun `an unauthenticated response carries them too`() = check("/api/shifts", 401)

    @Test
    fun `and so does a response to a route that does not exist`() = check("/actuator/does-not-exist", 404)
}
