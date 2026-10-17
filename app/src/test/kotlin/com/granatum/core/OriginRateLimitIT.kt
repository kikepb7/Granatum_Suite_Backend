package com.granatum.core

import com.granatum.core.domain.type.SecurityEventType
import com.granatum.core.infrastructure.database.repositories.SecurityEventRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Per-origin quotas on the public auth routes (feature 006, US1): FR-001,
 * FR-003 to FR-005, SC-001, SC-002.
 *
 * Runs with `forward-headers-strategy=native`, so each test can play several
 * clients through `X-Forwarded-For` - the request comes from 127.0.0.1, which
 * Tomcat trusts as a proxy. Each test uses its own addresses because the
 * limiter is shared by the whole context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "server.forward-headers-strategy=native",
        "security.rate-limits.login=3/1m",
        "security.rate-limits.session=3/1m",
        "security.rate-limits.sign-up=2/1h"
    ]
)
class OriginRateLimitIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var events: SecurityEventRepository

    private data class Response(val status: Int, val body: String, val retryAfter: String?)

    private fun post(path: String, json: String, from: String): Response =
        RestClient.create("http://localhost:$port").post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", from)
            .body(json)
            .exchange({ _, r ->
                Response(r.statusCode.value(), r.body.readAllBytes().decodeToString(), r.headers.getFirst("Retry-After"))
            }, false)!!

    private fun address() = "203.0.113.${(1..254).random()}"
    private val failedLogin = """{"email":"nobody-${UUID.randomUUID()}@granatum.es","password":"wrong"}"""

    @Test
    fun `past the sign-in quota the answer is 429 with Retry-After, and the password is never checked`() {
        val ip = address()
        repeat(3) { assertEquals(401, post("/api/auth/login", failedLogin, ip).status) }
        val before = events.countByType(SecurityEventType.LOGIN_UNKNOWN_ACCOUNT)

        val limited = post("/api/auth/login", failedLogin, ip)

        assertEquals(429, limited.status)
        assertTrue(limited.body.contains("\"code\":\"TOO_MANY_REQUESTS\""), limited.body)
        val wait = assertNotNull(limited.retryAfter).toLong()
        assertTrue(wait in 1..60, "Retry-After=$wait")
        assertEquals(before, events.countByType(SecurityEventType.LOGIN_UNKNOWN_ACCOUNT), "FR-003: it never reached the service")
    }

    /** SC-002. */
    @Test
    fun `another address is not affected while one is limited`() {
        val limited = "198.51.100.10"
        repeat(4) { post("/api/auth/login", failedLogin, limited) }
        assertEquals(429, post("/api/auth/login", failedLogin, limited).status)

        assertEquals(401, post("/api/auth/login", failedLogin, "198.51.100.11").status)
    }

    @Test
    fun `sign-up has its own quota, and it applies to the bootstrap code too`() {
        val ip = address()
        val body = """{"email":"x@granatum.es","password":"x","name":"x","identityDocument":"x","bootstrapCode":"guess"}"""
        repeat(2) { assertTrue(post("/api/auth/sign-up", body, ip).status != 429) }

        assertEquals(429, post("/api/auth/sign-up", body, ip).status)
        // Its own quota: sign-in from the same address still has room.
        assertEquals(401, post("/api/auth/login", failedLogin, ip).status)
    }

    @Test
    fun `refresh and logout share their own quota`() {
        val ip = address()
        val token = """{"refreshToken":"does-not-exist"}"""
        post("/api/auth/refresh", token, ip)
        post("/api/auth/logout", token, ip)
        post("/api/auth/refresh", token, ip)

        assertEquals(429, post("/api/auth/logout", token, ip).status)
    }
}
