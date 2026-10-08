package com.granatum.core

import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.database.repositories.EventoSeguridadRepository
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
        "seguridad.limites.login=3/1m",
        "seguridad.limites.sesion=3/1m",
        "seguridad.limites.registro=2/1h"
    ]
)
class LimitePorOrigenIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired lateinit var eventos: EventoSeguridadRepository

    private data class Respuesta(val estado: Int, val cuerpo: String, val retryAfter: String?)

    private fun post(ruta: String, json: String, desde: String): Respuesta =
        RestClient.create("http://localhost:$puerto").post().uri(ruta)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", desde)
            .body(json)
            .exchange({ _, r ->
                Respuesta(r.statusCode.value(), r.body.readAllBytes().decodeToString(), r.headers.getFirst("Retry-After"))
            }, false)!!

    private fun direccion() = "203.0.113.${(1..254).random()}"
    private val loginFallido = """{"email":"nadie-${UUID.randomUUID()}@granatum.es","password":"incorrecta"}"""

    @Test
    fun `past the sign-in quota the answer is 429 with Retry-After, and the password is never checked`() {
        val ip = direccion()
        repeat(3) { assertEquals(401, post("/api/auth/login", loginFallido, ip).estado) }
        val antes = eventos.countByTipo(TipoEventoSeguridad.LOGIN_CUENTA_DESCONOCIDA)

        val limitada = post("/api/auth/login", loginFallido, ip)

        assertEquals(429, limitada.estado)
        assertTrue(limitada.cuerpo.contains("\"code\":\"DEMASIADAS_PETICIONES\""), limitada.cuerpo)
        val espera = assertNotNull(limitada.retryAfter).toLong()
        assertTrue(espera in 1..60, "Retry-After=$espera")
        assertEquals(antes, eventos.countByTipo(TipoEventoSeguridad.LOGIN_CUENTA_DESCONOCIDA), "FR-003: it never reached the service")
    }

    /** SC-002. */
    @Test
    fun `another address is not affected while one is limited`() {
        val limitada = "198.51.100.10"
        repeat(4) { post("/api/auth/login", loginFallido, limitada) }
        assertEquals(429, post("/api/auth/login", loginFallido, limitada).estado)

        assertEquals(401, post("/api/auth/login", loginFallido, "198.51.100.11").estado)
    }

    @Test
    fun `sign-up has its own quota, and it applies to the bootstrap code too`() {
        val ip = direccion()
        val cuerpo = """{"email":"x@granatum.es","password":"x","nombre":"x","documentoIdentidad":"x","codigoArranque":"adivinanza"}"""
        repeat(2) { assertTrue(post("/api/auth/registro", cuerpo, ip).estado != 429) }

        assertEquals(429, post("/api/auth/registro", cuerpo, ip).estado)
        // Its own quota: sign-in from the same address still has room.
        assertEquals(401, post("/api/auth/login", loginFallido, ip).estado)
    }

    @Test
    fun `refresh and logout share their own quota`() {
        val ip = direccion()
        val token = """{"refreshToken":"no-existe"}"""
        post("/api/auth/refresh", token, ip)
        post("/api/auth/logout", token, ip)
        post("/api/auth/refresh", token, ip)

        assertEquals(429, post("/api/auth/logout", token, ip).estado)
    }
}
