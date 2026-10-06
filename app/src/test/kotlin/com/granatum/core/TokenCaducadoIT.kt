package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FR-006: an expired access token must be rejected **distinguishably** from an
 * invalid or absent one.
 *
 * ## Why it matters
 *
 * The application has to know whether to renew silently or to ask for the
 * password. Until this feature, `SecurityConfig` answered every rejection with
 * `HttpStatusEntryPoint(UNAUTHORIZED)`: a `401` with an **empty body**, no
 * `code`, and nothing to tell the cases apart - so a mobile client's only
 * option was to send the person back to the login screen every fifteen minutes,
 * which is exactly what the refresh token exists to avoid.
 *
 * That empty body was also a breach of principle VIII, which requires a single
 * `{code, message}` shape for every error. So this closes both.
 *
 * ## Why it lives in `app`
 *
 * The entry point is part of the global filter chain, which only exists here.
 * In `auth` there is no `SecurityConfig` at all, so the same test would pass
 * without ever exercising the rejection it claims to check (D-016).
 *
 * The expired token is minted by a second `JwtService` with a negative lifetime,
 * built with the **same signing key** as the application: a different key would
 * make the token invalid rather than expired, and the test would pass for the
 * wrong reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TokenCaducadoIT {

    @LocalServerPort
    var puerto: Int = 0

    @Value("\${jwt.secret}")
    lateinit var claveApp: String

    private data class Respuesta(val estado: Int, val cuerpo: String)

    private fun get(ruta: String, auth: String?): Respuesta =
        RestClient.builder().baseUrl("http://localhost:$puerto").build()
            .get().uri(ruta)
            .apply { if (auth != null) header("Authorization", auth) }
            .exchange({ _, response ->
                Respuesta(response.statusCode.value(), response.body.readAllBytes().decodeToString())
            }, false)!!

    private fun campo(cuerpo: String, nombre: String): String? =
        Regex("\"$nombre\"\\s*:\\s*\"([^\"]*)\"").find(cuerpo)?.groupValues?.get(1)

    /** A protected route; any one will do, the rejection happens before routing. */
    private val rutaProtegida = "/api/materiales"

    @Test
    fun `an expired access token answers TOKEN_ACCESO_EXPIRADO`() {
        val caducado = JwtService(
            secretBase64 = claveApp,
            expirationMinutes = -1,
            refreshExpirationDays = 30
        ).generateAccessToken(UUID.randomUUID(), Role.ADMIN)

        val respuesta = get(rutaProtegida, "Bearer $caducado")

        assertEquals(401, respuesta.estado)
        assertEquals(
            "TOKEN_ACCESO_EXPIRADO",
            campo(respuesta.cuerpo, "code"),
            "without this the client cannot tell 'renew' from 'ask for the password', " +
                "which is the whole of FR-006"
        )
        assertNotNull(campo(respuesta.cuerpo, "message"))
    }

    @Test
    fun `no Authorization header answers NO_AUTENTICADO`() {
        val respuesta = get(rutaProtegida, null)

        assertEquals(401, respuesta.estado)
        assertEquals("NO_AUTENTICADO", campo(respuesta.cuerpo, "code"))
    }

    /**
     * The distinction is the requirement, so it gets its own assertion: it would
     * be easy to satisfy both tests above with one code and lose the point.
     */
    @Test
    fun `the two rejections are distinguishable from each other`() {
        val caducado = JwtService(
            secretBase64 = claveApp,
            expirationMinutes = -1,
            refreshExpirationDays = 30
        ).generateAccessToken(UUID.randomUUID(), Role.EMPLEADO)

        val porCaducidad = campo(get(rutaProtegida, "Bearer $caducado").cuerpo, "code")
        val sinToken = campo(get(rutaProtegida, null).cuerpo, "code")

        assertTrue(
            porCaducidad != sinToken,
            "FR-006 is this inequality: $porCaducidad vs $sinToken"
        )
    }

    /**
     * A token signed with another key is **not** expired, it is forged, and it
     * must not be reported as a renewable session - that would tell an attacker
     * their guess was merely stale.
     */
    @Test
    fun `a token signed with another key answers NO_AUTENTICADO and not expired`() {
        val ajeno = JwtService(
            secretBase64 = randomTestJwtKeyBase64(),
            expirationMinutes = 15,
            refreshExpirationDays = 30
        ).generateAccessToken(UUID.randomUUID(), Role.ADMIN)

        val respuesta = get(rutaProtegida, "Bearer $ajeno")

        assertEquals(401, respuesta.estado)
        assertEquals("NO_AUTENTICADO", campo(respuesta.cuerpo, "code"))
    }

    @Test
    fun `garbage in the header answers NO_AUTENTICADO`() {
        val respuesta = get(rutaProtegida, "Bearer no-es-un-jwt")

        assertEquals(401, respuesta.estado)
        assertEquals("NO_AUTENTICADO", campo(respuesta.cuerpo, "code"))
    }

    /**
     * Principle VIII applies to the whole API, so the 403 must not be left as
     * the only mute response once the 401 carries a body.
     */
    @Test
    fun `a 403 also carries the stable error shape`() {
        val sinPermiso = JwtService(
            secretBase64 = claveApp,
            expirationMinutes = 15,
            refreshExpirationDays = 30
        ).generateAccessToken(UUID.randomUUID(), Role.EMPLEADO)

        // EMPLEADO has no inventory access at all (principle IV).
        val respuesta = get(rutaProtegida, "Bearer $sinPermiso")

        assertEquals(403, respuesta.estado)
        assertEquals("FORBIDDEN", campo(respuesta.cuerpo, "code"))
    }
}
