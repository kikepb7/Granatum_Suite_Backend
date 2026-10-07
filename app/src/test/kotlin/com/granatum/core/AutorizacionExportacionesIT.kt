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

/**
 * D-014: the export log and file verification are `ADMIN` only.
 *
 * Who exported whose register is itself sensitive - it says whose data went to
 * the Inspectorate or to the representatives - and the files sent for
 * verification are full of names and documents. The rule lives in
 * `SecurityConfig`, so it is checked here, through the real chain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AutorizacionExportacionesIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val cliente by lazy { RestClient.builder().baseUrl("http://localhost:$puerto").build() }

    private fun consultar(token: String?): Int =
        cliente.get().uri("/api/exportaciones")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .exchange({ _, r -> r.statusCode.value() }, false)!!

    private fun verificar(token: String?): Int =
        cliente.post().uri("/api/exportaciones/verificar")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .contentType(MediaType("text", "csv"))
            .body("Persona;Documento\r\n".toByteArray())
            .exchange({ _, r -> r.statusCode.value() }, false)!!

    private fun token(rol: Role) = jwtService.generateAccessToken(UUID.randomUUID(), rol)

    @Test
    fun `ENCARGADO, EMPLEADO and REPRESENTANTE are refused`() {
        listOf(Role.ENCARGADO, Role.EMPLEADO, Role.REPRESENTANTE).forEach { rol ->
            assertEquals(403, consultar(token(rol)), "GET as $rol")
            assertEquals(403, verificar(token(rol)), "POST verificar as $rol")
        }
    }

    @Test
    fun `without a token, 401`() {
        assertEquals(401, consultar(null))
        assertEquals(401, verificar(null))
    }

    @Test
    fun `ADMIN gets through`() {
        assertEquals(200, consultar(token(Role.ADMIN)))
        assertEquals(200, verificar(token(Role.ADMIN)))
    }
}
