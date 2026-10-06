package com.granatum.core

import com.granatum.core.ClientePruebaHttp.Companion.haceMinutos
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **SC-008**: a session pending a password change can do nothing but change it.
 *
 * Tested against the routes of *another* feature on purpose. `timetracking`
 * knows nothing about `auth`; the refusal has to come entirely from the
 * restricted authority the filter in `common` grants instead of the role, and
 * from rules in `SecurityConfig` that are all written as `hasAnyRole(...)`.
 * If it works here, it works for every feature without any of them changing.
 *
 * Only in `app`: in `auth` there is no global filter chain, so this test would
 * pass there without exercising any authorisation at all (D-016).
 *
 * The token is minted directly with the claim. What is under test is the
 * authorisation of a pending token, not how one is obtained - `auth` covers
 * that.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PendienteDeCambioBloqueaFichajeIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val http by lazy { ClientePruebaHttp(puerto) }

    private fun pendiente(rol: Role) =
        jwtService.generateAccessToken(UUID.randomUUID(), rol, requiereCambioPassword = true)

    @Test
    fun `a pending token cannot clock in`() {
        val r = http.post(
            "/api/fichajes/entrada",
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${haceMinutos(5)}"}""",
            pendiente(Role.EMPLEADO)
        )
        assertEquals(403, r.estado, "SC-008: the only operation allowed is changing the password")
    }

    @Test
    fun `a pending token cannot read the register either`() {
        assertEquals(403, http.get("/api/fichajes", pendiente(Role.EMPLEADO)).estado)
    }

    /**
     * The state of the session beats the role of the person: an ADMIN with a
     * temporary password is exactly as restricted as anybody else, or the
     * temporary password an administrator was handed by another administrator
     * would be a permanent all-access credential.
     */
    @Test
    fun `a pending ADMIN is restricted like anybody else`() {
        val token = pendiente(Role.ADMIN)

        assertEquals(403, http.get("/api/materiales", token).estado)
        assertEquals(403, http.get("/api/empleados", token).estado)
        assertEquals(
            403,
            http.post("/api/auth/cuentas", """{"empleadoId":"${UUID.randomUUID()}","email":"x@granatum.es"}""", token).estado,
            "a pending ADMIN must not be able to hand out access"
        )
    }

    /**
     * The other half. A pending token must reach `change-password`, or the
     * person is locked out for good. The body is deliberately invalid: getting
     * past the authorisation layer is the assertion, and anything other than
     * 401/403 proves it.
     */
    @Test
    fun `a pending token does reach change-password`() {
        val r = http.post("/api/auth/change-password", "{}", pendiente(Role.EMPLEADO))

        assertTrue(r.estado !in setOf(401, 403), "got ${r.estado}: ${r.cuerpo}")
    }
}
