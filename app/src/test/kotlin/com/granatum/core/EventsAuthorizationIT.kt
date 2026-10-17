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
class EventsAuthorizationIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var jwtService: JwtService

    private fun send(method: HttpMethod, path: String, role: Role?, json: String? = null): Int =
        RestClient.create("http://localhost:$port").method(method).uri(path)
            .apply { if (role != null) header("Authorization", "Bearer ${jwtService.generateAccessToken(UUID.randomUUID(), role)}") }
            .apply { if (json != null) contentType(MediaType.APPLICATION_JSON).body(json) }
            .exchange({ _, r -> r.statusCode.value() }, false)!!

    private val id = UUID.randomUUID()
    private val material = UUID.randomUUID()

    private val reads = listOf(
        Triple(HttpMethod.GET, "/api/events", null),
        Triple(HttpMethod.GET, "/api/events/$id", null),
        Triple(HttpMethod.GET, "/api/events/materials", null)
    )

    private val writes = listOf(
        Triple(HttpMethod.POST, "/api/events", """{"name":"Evento 1","date":"2099-01-01"}"""),
        Triple(HttpMethod.PUT, "/api/events/$id", """{"name":"Evento 1","date":"2099-01-01"}"""),
        Triple(HttpMethod.DELETE, "/api/events/$id", null),
        Triple(HttpMethod.POST, "/api/events/$id/finish", null),
        Triple(HttpMethod.PUT, "/api/events/$id/materials/$material", """{"quantity":1}"""),
        Triple(HttpMethod.DELETE, "/api/events/$id/materials/$material", null)
    )

    private fun allowed(status: Int) = status != 401 && status != 403

    @Test
    fun `When the staff uses the event routes then every role but REPRESENTATIVE reads and writes`() {
        listOf(Role.ADMIN, Role.MANAGER, Role.EMPLOYEE).forEach { role ->
            (reads + writes).forEach { (m, path, json) ->
                val status = send(m, path, role, json)
                assertTrue(allowed(status), "$role $m $path answered $status")
            }
        }
    }

    @Test
    fun `When REPRESENTATIVE uses the event routes then it reads but writes nothing`() {
        reads.forEach { (m, path, json) ->
            val status = send(m, path, Role.REPRESENTATIVE, json)
            assertTrue(allowed(status), "REPRESENTATIVE $m $path answered $status")
        }
        writes.forEach { (m, path, json) -> assertEquals(403, send(m, path, Role.REPRESENTATIVE, json), "$m $path") }
    }

    @Test
    fun `When there is no token then every event route is 401`() {
        (reads + writes).forEach { (m, path, json) -> assertEquals(401, send(m, path, null, json), "$m $path") }
    }

    /** Constitution v2.5.0 widens EMPLOYEE to events, not to the inventory. */
    @Test
    fun `When an EMPLOYEE tries the inventory routes then they are still forbidden`() {
        assertEquals(403, send(HttpMethod.GET, "/api/materials", Role.EMPLOYEE))
        assertEquals(403, send(HttpMethod.PATCH, "/api/materials/$material/quantity", Role.EMPLOYEE, """{"quantity":1,"reason":"x"}"""))
    }
}
