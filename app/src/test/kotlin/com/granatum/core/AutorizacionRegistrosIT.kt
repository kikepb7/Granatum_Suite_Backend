package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Feature 005: sign-up is public, managing sign-ups is ADMIN only (FR-015,
 * SC-007).
 *
 * In `app` because it is authorisation and `SecurityConfig` exists only here;
 * `auth`'s own tests run under a permit-all chain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AutorizacionRegistrosIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val http by lazy { ClientePruebaHttp(puerto) }

    private fun token(rol: Role) = jwtService.generateAccessToken(UUID.randomUUID(), rol)

    private val id = UUID.randomUUID()
    private val aprobar = """{"codigoVerificacion":"ABCDEFGH","rol":"EMPLEADO"}"""

    @Test
    fun `nobody but ADMIN may list, approve or reject sign-ups`() {
        listOf(Role.ENCARGADO, Role.EMPLEADO, Role.REPRESENTANTE).forEach { rol ->
            val t = token(rol)
            assertEquals(403, http.get("/api/auth/registros", t).estado, "$rol listing")
            assertEquals(403, http.post("/api/auth/registros/$id/aprobar", aprobar, t).estado, "$rol approving")
            assertEquals(403, http.post("/api/auth/registros/$id/rechazar", "{}", t).estado, "$rol rejecting")
        }
    }

    @Test
    fun `without a token managing sign-ups is 401`() {
        assertEquals(401, http.get("/api/auth/registros").estado)
        assertEquals(401, http.post("/api/auth/registros/$id/rechazar").estado)
    }

    @Test
    fun `ADMIN gets past the authorisation layer`() {
        val admin = token(Role.ADMIN)
        assertEquals(200, http.get("/api/auth/registros", admin).estado)
        // An unknown request: the service answers, not the security chain.
        assertEquals(404, http.post("/api/auth/registros/$id/rechazar", "{}", admin).estado)
    }

    /** Public: a malformed body is the validator's 400, never the chain's 401. */
    @Test
    fun `signing up needs no token`() {
        val estado = http.post("/api/auth/registro", "{}").estado
        assertEquals(400, estado)
        assertNotEquals(401, estado)
    }
}
