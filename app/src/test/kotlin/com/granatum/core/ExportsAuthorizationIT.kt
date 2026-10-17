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
class ExportsAuthorizationIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val client by lazy { RestClient.builder().baseUrl("http://localhost:$port").build() }

    private fun search(token: String?): Int =
        client.get().uri("/api/exports")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .exchange({ _, r -> r.statusCode.value() }, false)!!

    private fun verify(token: String?): Int =
        client.post().uri("/api/exports/verify")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .contentType(MediaType("text", "csv"))
            .body("Persona;Documento\r\n".toByteArray())
            .exchange({ _, r -> r.statusCode.value() }, false)!!

    private fun token(role: Role) = jwtService.generateAccessToken(UUID.randomUUID(), role)

    @Test
    fun `MANAGER, EMPLOYEE and REPRESENTATIVE are refused`() {
        listOf(Role.MANAGER, Role.EMPLOYEE, Role.REPRESENTATIVE).forEach { role ->
            assertEquals(403, search(token(role)), "GET as $role")
            assertEquals(403, verify(token(role)), "POST verify as $role")
        }
    }

    @Test
    fun `without a token, 401`() {
        assertEquals(401, search(null))
        assertEquals(401, verify(null))
    }

    @Test
    fun `ADMIN gets through`() {
        assertEquals(200, search(token(Role.ADMIN)))
        assertEquals(200, verify(token(Role.ADMIN)))
    }
}
