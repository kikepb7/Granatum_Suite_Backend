package com.granatum.core

import com.granatum.core.domain.type.Role
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The HTTP boundary of `/api/auth`, exercised with real JSON over a real socket.
 *
 * ## Why this exists
 *
 * This is the lesson of `ContratoHttpIT` in feature 001. Every other
 * integration test calls a service **method** directly, with Kotlin objects, so
 * none of them deserialises a request body - and that gap hid a complete outage:
 * Spring Boot 4's web layer uses Jackson 3 (`tools.jackson`) while the project
 * only carried the Kotlin module for Jackson 2, so **every POST answered 500**
 * with the entire suite green.
 *
 * A real port rather than MockMvc, and not only because Boot 4 removed
 * `@AutoConfigureMockMvc` and `TestRestTemplate`: MockMvc never opens a socket,
 * and what is under test here is precisely the wire - the converters, the
 * filters and the JSON.
 *
 * ## What it does not cover
 *
 * Authorisation. `SecurityConfig` lives in `app`, so this context has no chain
 * of its own and Boot's default security would otherwise answer `401` to
 * everything before a controller ran - which is what it did until
 * [SeguridadPermisivaTestConfig] was added. That configuration makes these tests
 * about the contract and leaves authorisation to `app`, the only module that has
 * both the real chain and the other features. It is the split D-016 calls for.
 */
@Testcontainers
@SpringBootTest(
    classes = [AuthTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(SeguridadPermisivaTestConfig::class)
class ContratoHttpAuthIT : BaseAuthIT() {

    @LocalServerPort
    var puerto: Int = 0

    private val password = "Granatum-2026!"

    private data class Respuesta(val estado: Int, val cuerpo: String)

    private val cliente: RestClient
        get() = RestClient.builder().baseUrl("http://localhost:$puerto").build()

    private fun post(ruta: String, json: String, auth: String? = null): Respuesta =
        cliente.post().uri(ruta)
            .contentType(MediaType.APPLICATION_JSON)
            .apply { if (auth != null) header("Authorization", auth) }
            .body(json)
            .exchange({ _, response ->
                Respuesta(response.statusCode.value(), response.body.readAllBytes().decodeToString())
            }, false)!!

    /** Deliberately a regex and not a JSON parse: it reads the raw wire bytes. */
    private fun campo(cuerpo: String, nombre: String): String? =
        Regex("\"$nombre\"\\s*:\\s*\"?([^\",}]*)\"?").find(cuerpo)?.groupValues?.get(1)

    @Test
    fun `login over HTTP returns the token pair as JSON`() {
        val cuenta = cuentaActiva(password = password, rol = Role.EMPLEADO)

        val respuesta = post(
            "/api/auth/login",
            """{"email":"${cuenta.email}","password":"$password"}"""
        )

        assertEquals(200, respuesta.estado, "cuerpo: ${respuesta.cuerpo}")
        assertNotNull(campo(respuesta.cuerpo, "accessToken"))
        assertNotNull(campo(respuesta.cuerpo, "refreshToken"))
        assertEquals("900", campo(respuesta.cuerpo, "expiresIn"))
        assertEquals("false", campo(respuesta.cuerpo, "requiereCambioPassword"))
    }

    /**
     * The body has to deserialise through the real converter. This is the
     * assertion that would have caught the Jackson 3 outage: a data class whose
     * primary constructor Jackson cannot use answers 500 here while every
     * service-level test stays green.
     */
    @Test
    fun `a wrong password answers 401 with the stable error shape`() {
        val cuenta = cuentaActiva(password = password)

        val respuesta = post(
            "/api/auth/login",
            """{"email":"${cuenta.email}","password":"Otra-Clave-2026!"}"""
        )

        assertEquals(401, respuesta.estado)
        assertEquals("CREDENCIALES_INVALIDAS", campo(respuesta.cuerpo, "code"))
        assertNotNull(campo(respuesta.cuerpo, "message"))
    }

    /**
     * FR-003 at the wire level, which is where it actually matters: the two
     * responses must be byte-identical, because that is what an attacker sees.
     */
    @Test
    fun `an unknown email answers byte for byte the same as a wrong password`() {
        val cuenta = cuentaActiva(password = password)

        val malPassword = post(
            "/api/auth/login",
            """{"email":"${cuenta.email}","password":"incorrecta"}"""
        )
        val sinCuenta = post(
            "/api/auth/login",
            """{"email":"${correoUnico()}","password":"incorrecta"}"""
        )

        assertEquals(malPassword.estado, sinCuenta.estado)
        assertEquals(
            malPassword.cuerpo,
            sinCuenta.cuerpo,
            "identical down to the bytes: anything that differs is an oracle for which " +
                "addresses are registered"
        )
    }

    @Test
    fun `a malformed email is rejected at the edge`() {
        val respuesta = post("/api/auth/login", """{"email":"no-es-un-correo","password":"x"}""")

        assertEquals(400, respuesta.estado, "@Valid rejects it before the service runs")
    }

    @Test
    fun `a missing field is rejected at the edge`() {
        val respuesta = post("/api/auth/login", """{"email":"ana@granatum.es"}""")

        assertEquals(400, respuesta.estado)
    }

    // --- refresh y logout ---------------------------------------------------

    @Test
    fun `refresh over HTTP returns a new pair as JSON`() {
        val cuenta = cuentaActiva(password = password)
        val login = post("/api/auth/login", """{"email":"${cuenta.email}","password":"$password"}""")
        val refresh = campo(login.cuerpo, "refreshToken")!!

        val respuesta = post("/api/auth/refresh", """{"refreshToken":"$refresh"}""")

        assertEquals(200, respuesta.estado, "cuerpo: ${respuesta.cuerpo}")
        assertNotNull(campo(respuesta.cuerpo, "accessToken"))
        assertTrue(
            campo(respuesta.cuerpo, "refreshToken") != refresh,
            "the token must rotate on the wire too, not only in the service"
        )
    }

    @Test
    fun `a reused refresh token answers 401 with its own code`() {
        val cuenta = cuentaActiva(password = password)
        val login = post("/api/auth/login", """{"email":"${cuenta.email}","password":"$password"}""")
        val refresh = campo(login.cuerpo, "refreshToken")!!
        post("/api/auth/refresh", """{"refreshToken":"$refresh"}""")

        val respuesta = post("/api/auth/refresh", """{"refreshToken":"$refresh"}""")

        assertEquals(401, respuesta.estado)
        assertEquals("TOKEN_RENOVACION_INVALIDO", campo(respuesta.cuerpo, "code"))
    }

    @Test
    fun `logout answers 204 with no body`() {
        val cuenta = cuentaActiva(password = password)
        val login = post("/api/auth/login", """{"email":"${cuenta.email}","password":"$password"}""")
        val refresh = campo(login.cuerpo, "refreshToken")!!

        val respuesta = post("/api/auth/logout", """{"refreshToken":"$refresh"}""")

        assertEquals(204, respuesta.estado)
        assertEquals("", respuesta.cuerpo, "204 means no content, so there must be none")
    }

    /** Idempotent over HTTP too: the second call is still a 204, not a 404. */
    @Test
    fun `logging out twice answers 204 both times`() {
        val cuenta = cuentaActiva(password = password)
        val login = post("/api/auth/login", """{"email":"${cuenta.email}","password":"$password"}""")
        val refresh = campo(login.cuerpo, "refreshToken")!!

        assertEquals(204, post("/api/auth/logout", """{"refreshToken":"$refresh"}""").estado)
        assertEquals(204, post("/api/auth/logout", """{"refreshToken":"$refresh"}""").estado)
    }

    @Test
    fun `an empty refresh token is rejected at the edge`() {
        assertEquals(400, post("/api/auth/refresh", """{"refreshToken":""}""").estado)
    }

    // --- alta y cambio de contrasena ----------------------------------------

    @Test
    fun `granting access over HTTP returns the temporary password once`() {
        val empleadoId = java.util.UUID.randomUUID().also { directorio.registrarActivo(it) }
        val email = correoUnico()

        val respuesta = post(
            "/api/auth/cuentas",
            """{"empleadoId":"$empleadoId","email":"$email","rol":"EMPLEADO"}"""
        )

        assertEquals(201, respuesta.estado, "cuerpo: ${respuesta.cuerpo}")
        assertEquals(email, campo(respuesta.cuerpo, "email"))
        assertEquals("EMPLEADO", campo(respuesta.cuerpo, "rol"))
        assertNotNull(
            campo(respuesta.cuerpo, "passwordTemporal"),
            "this response is the only place it ever exists: there is no second chance " +
                "to fetch it"
        )
    }

    @Test
    fun `granting access to somebody unknown answers 404 with its code`() {
        val respuesta = post(
            "/api/auth/cuentas",
            """{"empleadoId":"${java.util.UUID.randomUUID()}","email":"${correoUnico()}"}"""
        )

        assertEquals(404, respuesta.estado)
        assertEquals("EMPLEADO_NO_ENCONTRADO", campo(respuesta.cuerpo, "code"))
    }

    /**
     * 409 and not 400: it is the state of the system that blocks the operation,
     * not the shape of the request.
     */
    @Test
    fun `a duplicate account answers 409`() {
        val empleadoId = java.util.UUID.randomUUID().also { directorio.registrarActivo(it) }
        val cuerpo = """{"empleadoId":"$empleadoId","email":"${correoUnico()}"}"""
        post("/api/auth/cuentas", cuerpo)

        val respuesta = post(
            "/api/auth/cuentas",
            """{"empleadoId":"$empleadoId","email":"${correoUnico()}"}"""
        )

        assertEquals(409, respuesta.estado)
        assertEquals("CUENTA_YA_EXISTE", campo(respuesta.cuerpo, "code"))
    }

    /**
     * The only error in the feature with a third field, and therefore a declared
     * deviation from principle VIII. FR-023 requires saying *which* requirement
     * failed, and a client that marks form fields needs identifiers rather than
     * a sentence - so the field has to survive the trip over the wire.
     */
    @Test
    fun `a weak password answers 422 with the list of requirements`() {
        val (email, temporal) = accesoNuevo()
        val login = post("/api/auth/login", """{"email":"$email","password":"$temporal"}""")
        val acceso = campo(login.cuerpo, "accessToken")!!

        val respuesta = post(
            "/api/auth/change-password",
            """{"passwordActual":"$temporal","passwordNueva":"abc"}""",
            auth = "Bearer $acceso"
        )

        assertEquals(422, respuesta.estado, "cuerpo: ${respuesta.cuerpo}")
        assertEquals("PASSWORD_DEBIL", campo(respuesta.cuerpo, "code"))
        assertTrue(
            respuesta.cuerpo.contains("LONGITUD_MINIMA") &&
                respuesta.cuerpo.contains("FALTA_SIMBOLO"),
            "the `requisitos` field has to reach the client: ${respuesta.cuerpo}"
        )
        assertTrue(!respuesta.cuerpo.contains("abc"), "and must never quote the password")
    }

    @Test
    fun `changing the password over HTTP returns a pair with the flag cleared`() {
        val (email, temporal) = accesoNuevo()
        val login = post("/api/auth/login", """{"email":"$email","password":"$temporal"}""")
        assertEquals("true", campo(login.cuerpo, "requiereCambioPassword"))
        val acceso = campo(login.cuerpo, "accessToken")!!

        val respuesta = post(
            "/api/auth/change-password",
            """{"passwordActual":"$temporal","passwordNueva":"Granatum-2027!"}""",
            auth = "Bearer $acceso"
        )

        assertEquals(200, respuesta.estado, "cuerpo: ${respuesta.cuerpo}")
        assertEquals("false", campo(respuesta.cuerpo, "requiereCambioPassword"))
        assertNotNull(campo(respuesta.cuerpo, "accessToken"))
    }

    @Test
    fun `a wrong current password answers 401`() {
        val (email, temporal) = accesoNuevo()
        val login = post("/api/auth/login", """{"email":"$email","password":"$temporal"}""")
        val acceso = campo(login.cuerpo, "accessToken")!!

        val respuesta = post(
            "/api/auth/change-password",
            """{"passwordActual":"no-es-la-actual","passwordNueva":"Granatum-2027!"}""",
            auth = "Bearer $acceso"
        )

        assertEquals(401, respuesta.estado)
        assertEquals("CREDENCIALES_INVALIDAS", campo(respuesta.cuerpo, "code"))
    }

    // --- restablecimiento ---------------------------------------------------

    @Test
    fun `resetting over HTTP returns a new temporary password with 200`() {
        val (email, temporal) = accesoNuevo()
        val empleadoId = cuentas.findByEmail(email)!!.empleadoId

        val respuesta = post("/api/auth/cuentas/$empleadoId/restablecer", "")

        assertEquals(200, respuesta.estado, "cuerpo: ${respuesta.cuerpo}")
        val nueva = campo(respuesta.cuerpo, "passwordTemporal")
        assertNotNull(nueva)
        assertTrue(nueva != temporal)
    }

    @Test
    fun `resetting somebody without an account answers 404 with its code`() {
        val respuesta = post("/api/auth/cuentas/${java.util.UUID.randomUUID()}/restablecer", "")

        assertEquals(404, respuesta.estado)
        assertEquals("CUENTA_NO_ENCONTRADA", campo(respuesta.cuerpo, "code"))
    }

    /** Grants access through the API and returns the email and its temporary password. */
    private fun accesoNuevo(): Pair<String, String> {
        val empleadoId = java.util.UUID.randomUUID().also { directorio.registrarActivo(it) }
        val email = correoUnico()
        val alta = post(
            "/api/auth/cuentas",
            """{"empleadoId":"$empleadoId","email":"$email"}"""
        )
        return email to campo(alta.cuerpo, "passwordTemporal")!!
    }

    /**
     * The response is the only place the refresh token ever exists in clear, so
     * it must not also be leaking the stored hash - a client has no use for it
     * and it is the lookup key for a live session.
     */
    @Test
    fun `the response carries no hash and no password`() {
        val cuenta = cuentaActiva(password = password)

        val respuesta = post(
            "/api/auth/login",
            """{"email":"${cuenta.email}","password":"$password"}"""
        )

        assertTrue(!respuesta.cuerpo.contains(password), "the password must not echo back")
        assertTrue(!respuesta.cuerpo.contains("argon2"), "no password hash in the body")
        assertTrue(!respuesta.cuerpo.contains("tokenHash"), "no session hash in the body")
    }
}
