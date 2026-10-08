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
class ErroresSinTrazaIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired lateinit var jwtService: JwtService

    private data class Respuesta(val estado: Int, val cuerpo: String)

    private val admin by lazy { jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN) }

    private fun pedir(metodo: String, ruta: String, tipo: MediaType? = null, cuerpo: String? = null): Respuesta =
        RestClient.create("http://localhost:$puerto").method(org.springframework.http.HttpMethod.valueOf(metodo)).uri(ruta)
            .header("Authorization", "Bearer $admin")
            .apply { if (tipo != null) contentType(tipo) }
            .apply { if (cuerpo != null) body(cuerpo) }
            .exchange({ _, r -> Respuesta(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!

    private fun assertSinDetalleInterno(r: Respuesta) {
        listOf("trace", "exception", "com.granatum", "org.springframework", "java.", "timestamp", "\"path\"").forEach {
            assertFalse(r.cuerpo.contains(it), "the error body must not carry '$it': ${r.cuerpo}")
        }
    }

    @Test
    fun `an unknown route answers 404 RECURSO_NO_ENCONTRADO`() {
        val r = pedir("GET", "/api/no-existe-${UUID.randomUUID()}")

        assertEquals(404, r.estado)
        assertTrue(r.cuerpo.contains("\"code\":\"RECURSO_NO_ENCONTRADO\""), r.cuerpo)
        assertSinDetalleInterno(r)
    }

    @Test
    fun `a method the route does not support answers 405 METODO_NO_PERMITIDO`() {
        val r = pedir("DELETE", "/api/auth/login")

        assertEquals(405, r.estado)
        assertTrue(r.cuerpo.contains("\"code\":\"METODO_NO_PERMITIDO\""), r.cuerpo)
        assertSinDetalleInterno(r)
    }

    @Test
    fun `a content type the route does not accept carries no internal detail`() {
        val r = pedir("POST", "/api/auth/login", MediaType.TEXT_PLAIN, "hola")

        assertEquals(415, r.estado)
        assertSinDetalleInterno(r)
    }
}
