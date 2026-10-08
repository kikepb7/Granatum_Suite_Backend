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
class CabecerasSeguridadIT {

    @LocalServerPort
    var puerto: Int = 0

    private fun cabeceras(ruta: String): Pair<Int, HttpHeaders> =
        RestClient.create("http://localhost:$puerto").get().uri(ruta)
            .exchange({ _, r -> r.statusCode.value() to r.headers }, false)!!

    private val esperadas = mapOf(
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Content-Security-Policy" to "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        "Referrer-Policy" to "no-referrer",
        "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), payment=()"
    )

    private fun comprobar(ruta: String, estadoEsperado: Int) {
        val (estado, h) = cabeceras(ruta)
        assertEquals(estadoEsperado, estado, ruta)
        esperadas.forEach { (nombre, valor) -> assertEquals(valor, h.getFirst(nombre), "$nombre on $ruta ($estado)") }
        assertEquals(true, h.getFirst("Cache-Control")?.contains("no-store"), "Cache-Control on $ruta")
    }

    @Test
    fun `a successful response carries the security headers`() = comprobar("/actuator/health", 200)

    @Test
    fun `an unauthenticated response carries them too`() = comprobar("/api/fichajes", 401)

    @Test
    fun `and so does a response to a route that does not exist`() = comprobar("/actuator/no-existe", 404)
}
