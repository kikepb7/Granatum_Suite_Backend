package com.granatum.core

import com.granatum.core.ClientePruebaHttp.Companion.campo
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Every validation `400` in the API carries the contract's `{code, message}`
 * shape, with `code = VALIDACION`, no stack trace and **never the rejected value**.
 *
 * ## The hole this closes
 *
 * No class in the product produced `VALIDACION`, although the `auth` contract
 * promised it. Validation and missing-parameter errors came out with Spring's
 * default error body - and in `dev`, where devtools turns on stack traces and
 * binding errors, that body carried the whole trace and the **rejected value**:
 * a malformed email came back in the response, and an over-long new password
 * in `change-password` would have come back in clear. `auth`'s tests only
 * checked the `400` status, which is why nobody saw it. Found while planning
 * feature 003 (research.md D-019).
 *
 * ## Why those properties
 *
 * devtools is `developmentOnly`, so it is absent from the test classpath and
 * the trace would not appear here even with the hole open. The properties below
 * switch on exactly what devtools switches on in `dev`, so this test proves the
 * handler wins in the environment where the leak was real - not merely in one
 * where Spring happens to be quiet.
 *
 * Every case asserts the **body**, not just the status: a test that only reads
 * the status code is what let this through the first time.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "server.error.include-stacktrace=always",
        "server.error.include-message=always",
        "server.error.include-binding-errors=always",
        "server.error.include-exception=true"
    ]
)
class ValidacionFormatoIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val http by lazy { ClientePruebaHttp(puerto) }

    private fun token(rol: Role) = jwtService.generateAccessToken(UUID.randomUUID(), rol)

    private fun comprobar(r: ClientePruebaHttp.Respuesta, valorEnviado: String? = null) {
        assertEquals(400, r.estado, r.cuerpo)
        assertEquals(
            "VALIDACION",
            campo(r.cuerpo, "code"),
            "a validation error must use the contract's shape, not Spring's default: ${r.cuerpo.take(300)}"
        )
        assertFalse(r.cuerpo.contains("\"trace\""), "no stack trace in a response: ${r.cuerpo.take(300)}")
        assertFalse(r.cuerpo.contains("\tat "), "no stack frames in a response")
        if (valorEnviado != null) {
            assertFalse(
                r.cuerpo.contains(valorEnviado),
                "the rejected value must never be echoed back - it can be a password"
            )
        }
    }

    @Test
    fun `an invalid request body field answers VALIDACION without echoing the value`() {
        val correo = "no-es-un-correo-${UUID.randomUUID()}"

        comprobar(
            http.post("/api/auth/login", """{"email":"$correo","password":"x"}"""),
            valorEnviado = correo
        )
    }

    @Test
    fun `an over-long password is refused without echoing it`() {
        val larga = "Clave-Secreta-" + "x".repeat(140)

        comprobar(
            http.post("/api/auth/login", """{"email":"ana@granatum.es","password":"$larga"}"""),
            valorEnviado = larga
        )
    }

    @Test
    fun `a missing request parameter answers VALIDACION`() {
        comprobar(
            http.get("/api/fichajes/empleado/${UUID.randomUUID()}/resumen", token(Role.ADMIN))
        )
    }

    @Test
    fun `a parameter of the wrong type answers VALIDACION`() {
        comprobar(
            http.get(
                "/api/fichajes/empleado/${UUID.randomUUID()}/resumen?anio=no-es-un-numero&mes=1",
                token(Role.ADMIN)
            ),
            valorEnviado = "no-es-un-numero"
        )
    }

    /** Feature 003: the export's dates are request parameters like any other. */
    @Test
    fun `an export without its dates, or with a malformed one, answers VALIDACION`() {
        comprobar(http.get("/api/fichajes/export?hasta=2026-10-31", token(Role.ADMIN)))
        comprobar(
            http.get("/api/fichajes/export?desde=no-es-fecha&hasta=2026-10-31", token(Role.ADMIN)),
            valorEnviado = "no-es-fecha"
        )
    }

    @Test
    fun `an unreadable JSON body answers VALIDACION`() {
        comprobar(
            http.post("/api/categorias", """{"nombre": "sin cerrar""", token(Role.ENCARGADO))
        )
    }
}
