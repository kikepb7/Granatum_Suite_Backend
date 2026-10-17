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
class AbsencesAuthorizationIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var jwtService: JwtService

    private fun request(method: HttpMethod, path: String, role: Role?, json: String? = null): Int =
        RestClient.create("http://localhost:$port").method(method).uri(path)
            .apply { if (role != null) header("Authorization", "Bearer ${jwtService.generateAccessToken(UUID.randomUUID(), role)}") }
            .apply { if (json != null) contentType(MediaType.APPLICATION_JSON).body(json) }
            .exchange({ _, r -> r.statusCode.value() }, false)!!

    private val id = UUID.randomUUID()
    private val all = listOf(
        Triple(HttpMethod.GET, "/api/absences", null),
        Triple(HttpMethod.GET, "/api/absences/balance", null),
        Triple(HttpMethod.GET, "/api/absences/$id", null),
        Triple(HttpMethod.POST, "/api/absences", """{"type":"VACATION","from":"2030-01-01","to":"2030-01-02"}"""),
        Triple(HttpMethod.POST, "/api/absences/$id/cancel", "{}"),
        Triple(HttpMethod.POST, "/api/absences/record", """{"employeeId":"$id","type":"SICK_LEAVE","from":"2030-01-01"}"""),
        Triple(HttpMethod.POST, "/api/absences/$id/approve", "{}"),
        Triple(HttpMethod.POST, "/api/absences/$id/reject", """{"reason":"no"}"""),
        Triple(HttpMethod.POST, "/api/absences/$id/discharge", """{"to":"2030-01-05"}"""),
        Triple(HttpMethod.PUT, "/api/absences/entitlements/$id/2030", """{"days":20}""")
    )

    @Test
    fun `REPRESENTATIVE reaches no absence route`() {
        all.forEach { (m, path, json) -> assertEquals(403, request(m, path, Role.REPRESENTATIVE, json), "$m $path") }
    }

    @Test
    fun `without a token every absence route is 401`() {
        all.forEach { (m, path, json) -> assertEquals(401, request(m, path, null, json), "$m $path") }
    }

    @Test
    fun `an EMPLOYEE may request, cancel and see, but not register, resolve or set entitlements`() {
        all.take(5).forEach { (m, path, json) ->
            val status = request(m, path, Role.EMPLOYEE, json)
            assertTrue(status != 401 && status != 403, "$m $path answered $status")
        }
        all.drop(5).forEach { (m, path, json) -> assertEquals(403, request(m, path, Role.EMPLOYEE, json), "$m $path") }
    }

    @Test
    fun `a MANAGER resolves and registers, but only the ADMIN sets entitlements`() {
        all.dropLast(1).forEach { (m, path, json) ->
            val status = request(m, path, Role.MANAGER, json)
            assertTrue(status != 401 && status != 403, "$m $path answered $status")
        }
        val (m, path, json) = all.last()
        assertEquals(403, request(m, path, Role.MANAGER, json))
        val admin = request(m, path, Role.ADMIN, json)
        assertTrue(admin != 401 && admin != 403, "ADMIN setting an entitlement answered $admin")
    }
}
