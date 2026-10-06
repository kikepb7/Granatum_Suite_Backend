package com.granatum.core

import com.granatum.core.ClientePruebaHttp.Companion.campo
import com.granatum.core.ClientePruebaHttp.Companion.dniValido
import com.granatum.core.ClientePruebaHttp.Companion.haceMinutos
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * **SC-001 - the criterion that gives this feature its reason to exist.**
 *
 * A person is registered, given access, changes their temporary password,
 * signs in and completes a working day - and the fichaje is attributed to them
 * **without the client ever sending an identifier**. Before this feature the
 * identity behind every fichaje came from `/api/dev/token`, which issues any
 * role to anyone, so no record in the working-time register was reliably
 * attributable to a person.
 *
 * ## Why it can only live in `app`
 *
 * It needs `auth`, `timetracking` and the real `SecurityConfig` together, and
 * `app` is the only module that has all three.
 *
 * ## The one shortcut, and why it is not the one SC-001 forbids
 *
 * The `ADMIN` who registers the person is minted straight from `JwtService`.
 * Somebody has to be the first administrator, and that is a bootstrap concern,
 * not the subject of this test. What SC-001 rules out is the **employee**
 * reaching the register through the development emitter, and every step the
 * employee takes here goes through the real sign-in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginYFichajeIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val http by lazy { ClientePruebaHttp(puerto) }

    private val admin by lazy { jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN) }

    @Test
    fun `a person signs in for real and completes a working day`() {
        // 1. Registered as staff (timetracking).
        val empleado = http.post(
            "/api/empleados",
            """{"nombre":"Persona SC-001","documentoIdentidad":"${dniValido()}",
                "puesto":"Florista","tipoContrato":"JORNADA_COMPLETA","fechaAlta":"2026-10-01"}""",
            admin
        )
        assertEquals(201, empleado.estado, empleado.cuerpo)
        val empleadoId = campo(empleado.cuerpo, "id")!!

        // 2. Given access, in one operation (auth).
        val email = "sc001-${UUID.randomUUID()}@granatum.es"
        val alta = http.post(
            "/api/auth/cuentas",
            """{"empleadoId":"$empleadoId","email":"$email"}""",
            admin
        )
        assertEquals(201, alta.estado, alta.cuerpo)
        val temporal = campo(alta.cuerpo, "passwordTemporal")!!

        // 3. First sign-in: pending change.
        val primero = http.post("/api/auth/login", """{"email":"$email","password":"$temporal"}""")
        assertEquals(200, primero.estado, primero.cuerpo)
        assertEquals("true", campo(primero.cuerpo, "requiereCambioPassword"))

        // 4. Changes the temporary password.
        val cambio = http.post(
            "/api/auth/change-password",
            """{"passwordActual":"$temporal","passwordNueva":"Granatum-2026!"}""",
            campo(primero.cuerpo, "accessToken")
        )
        assertEquals(200, cambio.estado, cambio.cuerpo)
        val acceso = campo(cambio.cuerpo, "accessToken")!!

        // 5. Clocks in. No employee id anywhere in the request.
        val entrada = http.post(
            "/api/fichajes/entrada",
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${haceMinutos(90)}"}""",
            acceso
        )
        assertEquals(201, entrada.estado, entrada.cuerpo)
        assertEquals(
            empleadoId,
            campo(entrada.cuerpo, "empleadoId"),
            "the fichaje must belong to the person who signed in, derived from the token " +
                "subject alone - that is what makes the register attributable"
        )
        val fichajeId = assertNotNull(campo(entrada.cuerpo, "id"))

        // 6. Clocks out.
        val salida = http.post(
            "/api/fichajes/$fichajeId/salida",
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${haceMinutos(1)}"}""",
            acceso
        )
        assertEquals(200, salida.estado, salida.cuerpo)
        assertEquals("CERRADO", campo(salida.cuerpo, "estado"))
    }
}
