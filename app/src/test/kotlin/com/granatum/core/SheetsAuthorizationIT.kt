package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Feature 012 (FR-024, SC-006): the Google Sheets copy is `ADMIN` only -
 * configuring, exporting, resyncing and reading the status. The rule lives in
 * `SecurityConfig`, so it is checked here, through the real chain.
 *
 * The test context has no Google credentials, so an ADMIN reaching the export
 * gets `503 SHEETS_UNAVAILABLE`: past security, which is what is checked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SheetsAuthorizationIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val client by lazy { RestClient.builder().baseUrl("http://localhost:$port").build() }

    private fun call(method: HttpMethod, path: String, token: String?): Int =
        client.method(method).uri(path)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .exchange({ _, r -> r.statusCode.value() }, false)!!

    private fun token(role: Role) = jwtService.generateAccessToken(UUID.randomUUID(), role)

    private val routes = listOf(
        HttpMethod.GET to "/api/sheets/settings",
        HttpMethod.POST to "/api/sheets/shifts/2026/10",
        HttpMethod.POST to "/api/sheets/invoices/2026/10/resync",
        HttpMethod.GET to "/api/sheets/status",
        HttpMethod.POST to "/api/sheets/tasks/retry-failed"
    )

    @Test
    fun `MANAGER, EMPLOYEE and REPRESENTATIVE are refused`() {
        listOf(Role.MANAGER, Role.EMPLOYEE, Role.REPRESENTATIVE).forEach { role ->
            routes.forEach { (method, path) ->
                assertEquals(403, call(method, path, token(role)), "$method $path as $role")
            }
        }
    }

    @Test
    fun `without a token, 401`() {
        routes.forEach { (method, path) -> assertEquals(401, call(method, path, null), "$method $path") }
    }

    @Test
    fun `ADMIN gets through`() {
        assertEquals(200, call(HttpMethod.GET, "/api/sheets/settings", token(Role.ADMIN)))
        assertEquals(503, call(HttpMethod.POST, "/api/sheets/shifts/2026/10", token(Role.ADMIN)), "past security: no credentials")
        assertEquals(200, call(HttpMethod.GET, "/api/sheets/status", token(Role.ADMIN)))
    }
}
