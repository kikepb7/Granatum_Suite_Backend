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
import kotlin.test.assertTrue

/**
 * FR-018 and FR-024: only an `ADMIN` creates or resets credentials (US5
 * scenario 4).
 *
 * Lives in `app` because it is authorisation, and `SecurityConfig` exists only
 * here. In `auth` the module's own tests run under a permit-all chain, so the
 * same assertions there would pass without exercising any rule (D-016).
 *
 * Only the authorisation layer is under test, so the ADMIN cases accept any
 * answer that is not 401/403 - what the service then says about a random
 * empleado id is `auth`'s business, tested there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AutorizacionCuentasIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private fun post(ruta: String, rol: Role?, json: String = "{}"): Int =
        RestClient.builder().baseUrl("http://localhost:$puerto").build()
            .post().uri(ruta)
            .contentType(MediaType.APPLICATION_JSON)
            .apply {
                if (rol != null) {
                    header("Authorization", "Bearer ${jwtService.generateAccessToken(UUID.randomUUID(), rol)}")
                }
            }
            .body(json)
            .exchange({ _, response -> response.statusCode.value() }, false)!!

    private val alta = "/api/auth/cuentas"
    private fun restablecer() = "/api/auth/cuentas/${UUID.randomUUID()}/restablecer"
    private val cuerpoAlta = """{"empleadoId":"${UUID.randomUUID()}","email":"x@granatum.es"}"""

    @Test
    fun `nobody but ADMIN may grant access`() {
        listOf(Role.ENCARGADO, Role.EMPLEADO, Role.REPRESENTANTE).forEach { rol ->
            assertEquals(403, post(alta, rol, cuerpoAlta), "$rol must not grant access")
        }
    }

    /** US5 scenario 4. */
    @Test
    fun `nobody but ADMIN may reset a password`() {
        listOf(Role.ENCARGADO, Role.EMPLEADO, Role.REPRESENTANTE).forEach { rol ->
            assertEquals(
                403,
                post(restablecer(), rol),
                "$rol resetting someone else's password would be a takeover of their account"
            )
        }
    }

    @Test
    fun `without a token the answer is 401 and not 403`() {
        assertEquals(401, post(alta, null, cuerpoAlta))
        assertEquals(401, post(restablecer(), null))
    }

    @Test
    fun `ADMIN gets past the authorisation layer`() {
        val estadoAlta = post(alta, Role.ADMIN, cuerpoAlta)
        val estadoReset = post(restablecer(), Role.ADMIN)

        assertTrue(estadoAlta !in setOf(401, 403), "alta: $estadoAlta")
        assertTrue(estadoReset !in setOf(401, 403), "restablecer: $estadoReset")
    }
}
