package com.granatum.core

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 006, FR-012, FR-013: only the configured browser origins. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["security.cors.allowed-origins=https://app.granatum.es, https://admin.granatum.es"])
class CorsIT {

    @LocalServerPort
    var port: Int = 0

    private fun preflight(origin: String): Pair<Int, HttpHeaders> =
        RestClient.create("http://localhost:$port").method(HttpMethod.OPTIONS).uri("/api/auth/login")
            .header("Origin", origin)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type,authorization")
            .exchange({ _, r -> r.statusCode.value() to r.headers }, false)!!

    @Test
    fun `a configured origin passes the preflight with the methods and headers the API uses`() {
        val (status, h) = preflight("https://app.granatum.es")

        assertEquals(200, status)
        assertEquals("https://app.granatum.es", h.getFirst("Access-Control-Allow-Origin"))
        val methods = h.getFirst("Access-Control-Allow-Methods")!!
        listOf("GET", "POST", "PUT", "PATCH", "DELETE").forEach { assertTrue(methods.contains(it), methods) }
        val allowed = h.getFirst("Access-Control-Allow-Headers")!!.lowercase()
        assertTrue(allowed.contains("authorization") && allowed.contains("content-type"), allowed)
        assertNull(h.getFirst("Access-Control-Allow-Credentials"), "the token travels in a header, not a cookie")
    }

    @Test
    fun `the second configured origin is accepted too`() {
        assertEquals(200, preflight("https://admin.granatum.es").first)
    }

    @Test
    fun `any other origin is refused`() {
        val (status, h) = preflight("https://attacker.example")

        assertEquals(403, status)
        assertNull(h.getFirst("Access-Control-Allow-Origin"))
    }

    @Test
    fun `a real response exposes the headers clients need to read`() {
        val exposed = RestClient.create("http://localhost:$port").post().uri("/api/auth/login")
            .header("Origin", "https://app.granatum.es")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"email":"nobody@granatum.es","password":"wrong"}""")
            .exchange({ _, r -> r.headers.getFirst("Access-Control-Expose-Headers") }, false)!!

        assertTrue(exposed.contains("Content-Disposition") && exposed.contains("Retry-After"), exposed)
    }
}
