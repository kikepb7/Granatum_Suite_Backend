package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Feature 006, FR-009, SC-004: what nobody handles still answers `{code,
 * message}`, and never with a trace, an exception name or a package.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorsWithoutTraceIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var jwtService: JwtService

    private data class Response(val status: Int, val body: String)

    private val admin by lazy { jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN) }

    private fun request(method: String, path: String, type: MediaType? = null, content: String? = null): Response =
        RestClient.create("http://localhost:$port").method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
            .header("Authorization", "Bearer $admin")
            .apply { if (type != null) contentType(type) }
            .apply { if (content != null) body(content) }
            .exchange({ _, r -> Response(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!

    private fun assertNoInternalDetail(r: Response) {
        listOf("trace", "exception", "com.granatum", "org.springframework", "java.", "timestamp", "\"path\"").forEach {
            assertFalse(r.body.contains(it), "the error body must not carry '$it': ${r.body}")
        }
    }

    @Test
    fun `an unknown route answers 404 RESOURCE_NOT_FOUND`() {
        val r = request("GET", "/api/does-not-exist-${UUID.randomUUID()}")

        assertEquals(404, r.status)
        assertTrue(r.body.contains("\"code\":\"RESOURCE_NOT_FOUND\""), r.body)
        assertNoInternalDetail(r)
    }

    @Test
    fun `a method the route does not support answers 405 METHOD_NOT_ALLOWED`() {
        val r = request("DELETE", "/api/auth/login")

        assertEquals(405, r.status)
        assertTrue(r.body.contains("\"code\":\"METHOD_NOT_ALLOWED\""), r.body)
        assertNoInternalDetail(r)
    }

    @Test
    fun `a content type the route does not accept carries no internal detail`() {
        val r = request("POST", "/api/auth/login", MediaType.TEXT_PLAIN, "hola")

        assertEquals(415, r.status)
        assertNoInternalDetail(r)
    }
}
