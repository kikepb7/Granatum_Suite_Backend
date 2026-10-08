package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feature 007, FR-003, FR-006, FR-019, SC-004: who reaches which absence route.
 * In `app` because it is authorisation, and `SecurityConfig` only exists here.
 * Only the security layer is under test: for an allowed role any answer that is
 * not 401/403 will do - what the service says about a random id is the
 * module's business.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AutorizacionAusenciasIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired lateinit var jwtService: JwtService

    private fun pedir(metodo: HttpMethod, ruta: String, rol: Role?, json: String? = null): Int =
        RestClient.create("http://localhost:$puerto").method(metodo).uri(ruta)
            .apply { if (rol != null) header("Authorization", "Bearer ${jwtService.generateAccessToken(UUID.randomUUID(), rol)}") }
            .apply { if (json != null) contentType(MediaType.APPLICATION_JSON).body(json) }
            .exchange({ _, r -> r.statusCode.value() }, false)!!

    private val id = UUID.randomUUID()
    private val todas = listOf(
        Triple(HttpMethod.GET, "/api/ausencias", null),
        Triple(HttpMethod.GET, "/api/ausencias/saldo", null),
        Triple(HttpMethod.GET, "/api/ausencias/$id", null),
        Triple(HttpMethod.POST, "/api/ausencias", """{"tipo":"VACACIONES","desde":"2030-01-01","hasta":"2030-01-02"}"""),
        Triple(HttpMethod.POST, "/api/ausencias/$id/cancelar", "{}"),
        Triple(HttpMethod.POST, "/api/ausencias/registro", """{"empleadoId":"$id","tipo":"BAJA_MEDICA","desde":"2030-01-01"}"""),
        Triple(HttpMethod.POST, "/api/ausencias/$id/aprobar", "{}"),
        Triple(HttpMethod.POST, "/api/ausencias/$id/rechazar", """{"motivo":"no"}"""),
        Triple(HttpMethod.POST, "/api/ausencias/$id/alta", """{"hasta":"2030-01-05"}"""),
        Triple(HttpMethod.PUT, "/api/ausencias/derechos/$id/2030", """{"dias":20}""")
    )

    @Test
    fun `REPRESENTANTE reaches no absence route`() {
        todas.forEach { (m, ruta, json) -> assertEquals(403, pedir(m, ruta, Role.REPRESENTANTE, json), "$m $ruta") }
    }

    @Test
    fun `without a token every absence route is 401`() {
        todas.forEach { (m, ruta, json) -> assertEquals(401, pedir(m, ruta, null, json), "$m $ruta") }
    }

    @Test
    fun `an EMPLEADO may request, cancel and see, but not register, resolve or set entitlements`() {
        todas.take(5).forEach { (m, ruta, json) ->
            val estado = pedir(m, ruta, Role.EMPLEADO, json)
            assertTrue(estado != 401 && estado != 403, "$m $ruta answered $estado")
        }
        todas.drop(5).forEach { (m, ruta, json) -> assertEquals(403, pedir(m, ruta, Role.EMPLEADO, json), "$m $ruta") }
    }

    @Test
    fun `an ENCARGADO resolves and registers, but only the ADMIN sets entitlements`() {
        todas.dropLast(1).forEach { (m, ruta, json) ->
            val estado = pedir(m, ruta, Role.ENCARGADO, json)
            assertTrue(estado != 401 && estado != 403, "$m $ruta answered $estado")
        }
        val (m, ruta, json) = todas.last()
        assertEquals(403, pedir(m, ruta, Role.ENCARGADO, json))
        val admin = pedir(m, ruta, Role.ADMIN, json)
        assertTrue(admin != 401 && admin != 403, "ADMIN setting an entitlement answered $admin")
    }
}
