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
 * Feature 011, FR-011, SC-004 and constitution v2.5.0: who reaches which event route. In `app`
 * because it is authorisation, and `SecurityConfig` only exists here. Only the security layer is
 * under test: for an allowed role any answer that is not 401/403 will do.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AutorizacionEventosIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired lateinit var jwtService: JwtService

    private fun pedir(metodo: HttpMethod, ruta: String, rol: Role?, json: String? = null): Int =
        RestClient.create("http://localhost:$puerto").method(metodo).uri(ruta)
            .apply { if (rol != null) header("Authorization", "Bearer ${jwtService.generateAccessToken(UUID.randomUUID(), rol)}") }
            .apply { if (json != null) contentType(MediaType.APPLICATION_JSON).body(json) }
            .exchange({ _, r -> r.statusCode.value() }, false)!!

    private val id = UUID.randomUUID()
    private val material = UUID.randomUUID()

    private val lecturas = listOf(
        Triple(HttpMethod.GET, "/api/eventos", null),
        Triple(HttpMethod.GET, "/api/eventos/$id", null),
        Triple(HttpMethod.GET, "/api/eventos/materiales", null)
    )

    private val escrituras = listOf(
        Triple(HttpMethod.POST, "/api/eventos", """{"nombre":"Evento 1","fecha":"2099-01-01"}"""),
        Triple(HttpMethod.PUT, "/api/eventos/$id", """{"nombre":"Evento 1","fecha":"2099-01-01"}"""),
        Triple(HttpMethod.DELETE, "/api/eventos/$id", null),
        Triple(HttpMethod.POST, "/api/eventos/$id/finalizar", null),
        Triple(HttpMethod.PUT, "/api/eventos/$id/materiales/$material", """{"cantidad":1}"""),
        Triple(HttpMethod.DELETE, "/api/eventos/$id/materiales/$material", null)
    )

    private fun permitido(estado: Int) = estado != 401 && estado != 403

    @Test
    fun `When the staff uses the event routes then every role but REPRESENTANTE reads and writes`() {
        listOf(Role.ADMIN, Role.ENCARGADO, Role.EMPLEADO).forEach { rol ->
            (lecturas + escrituras).forEach { (m, ruta, json) ->
                val estado = pedir(m, ruta, rol, json)
                assertTrue(permitido(estado), "$rol $m $ruta answered $estado")
            }
        }
    }

    @Test
    fun `When REPRESENTANTE uses the event routes then it reads but writes nothing`() {
        lecturas.forEach { (m, ruta, json) ->
            val estado = pedir(m, ruta, Role.REPRESENTANTE, json)
            assertTrue(permitido(estado), "REPRESENTANTE $m $ruta answered $estado")
        }
        escrituras.forEach { (m, ruta, json) -> assertEquals(403, pedir(m, ruta, Role.REPRESENTANTE, json), "$m $ruta") }
    }

    @Test
    fun `When there is no token then every event route is 401`() {
        (lecturas + escrituras).forEach { (m, ruta, json) -> assertEquals(401, pedir(m, ruta, null, json), "$m $ruta") }
    }

    /** Constitution v2.5.0 widens EMPLEADO to events, not to the inventory. */
    @Test
    fun `When an EMPLEADO tries the inventory routes then they are still forbidden`() {
        assertEquals(403, pedir(HttpMethod.GET, "/api/materiales", Role.EMPLEADO))
        assertEquals(403, pedir(HttpMethod.PATCH, "/api/materiales/$material/cantidad", Role.EMPLEADO, """{"cantidad":1,"motivo":"x"}"""))
    }
}
