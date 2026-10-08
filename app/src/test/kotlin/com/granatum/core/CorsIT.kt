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
@TestPropertySource(properties = ["seguridad.cors.origenes=https://app.granatum.es, https://admin.granatum.es"])
class CorsIT {

    @LocalServerPort
    var puerto: Int = 0

    private fun preflight(origen: String): Pair<Int, HttpHeaders> =
        RestClient.create("http://localhost:$puerto").method(HttpMethod.OPTIONS).uri("/api/auth/login")
            .header("Origin", origen)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type,authorization")
            .exchange({ _, r -> r.statusCode.value() to r.headers }, false)!!

    @Test
    fun `a configured origin passes the preflight with the methods and headers the API uses`() {
        val (estado, h) = preflight("https://app.granatum.es")

        assertEquals(200, estado)
        assertEquals("https://app.granatum.es", h.getFirst("Access-Control-Allow-Origin"))
        val metodos = h.getFirst("Access-Control-Allow-Methods")!!
        listOf("GET", "POST", "PUT", "PATCH", "DELETE").forEach { assertTrue(metodos.contains(it), metodos) }
        val permitidas = h.getFirst("Access-Control-Allow-Headers")!!.lowercase()
        assertTrue(permitidas.contains("authorization") && permitidas.contains("content-type"), permitidas)
        assertNull(h.getFirst("Access-Control-Allow-Credentials"), "the token travels in a header, not a cookie")
    }

    @Test
    fun `the second configured origin is accepted too`() {
        assertEquals(200, preflight("https://admin.granatum.es").first)
    }

    @Test
    fun `any other origin is refused`() {
        val (estado, h) = preflight("https://atacante.example")

        assertEquals(403, estado)
        assertNull(h.getFirst("Access-Control-Allow-Origin"))
    }

    @Test
    fun `a real response exposes the headers clients need to read`() {
        val expuestas = RestClient.create("http://localhost:$puerto").post().uri("/api/auth/login")
            .header("Origin", "https://app.granatum.es")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"email":"nadie@granatum.es","password":"incorrecta"}""")
            .exchange({ _, r -> r.headers.getFirst("Access-Control-Expose-Headers") }, false)!!

        assertTrue(expuestas.contains("Content-Disposition") && expuestas.contains("Retry-After"), expuestas)
    }
}
