package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * US4, SC-005, FR-026: every invoicing route is ADMIN only.
 *
 * Invoices say whom the company buys from and sells to, for how much, and carry
 * the DNI of self-employed suppliers. MANAGER runs inventory and approves
 * shifts, but has no business here. Checked through the real security chain,
 * for **every** route of contracts/README.md including the multipart upload,
 * so a route added without its rule fails here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvoicingAuthorizationIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val client by lazy { RestClient.builder().baseUrl("http://localhost:$port").build() }

    private data class Route(val method: HttpMethod, val path: String, val body: Any? = null, val multipart: Boolean = false)

    private val id = UUID.randomUUID()
    private val version = """{"version":0}"""

    private val routes = listOf(
        Route(HttpMethod.GET, "/api/invoicing/company"),
        Route(HttpMethod.PUT, "/api/invoicing/company", """{"legalName":"X","nif":"B12345674"}"""),
        Route(HttpMethod.POST, "/api/invoicing/invoices", multipart = true),
        Route(HttpMethod.GET, "/api/invoicing/invoices"),
        Route(HttpMethod.GET, "/api/invoicing/invoices/$id"),
        Route(HttpMethod.PUT, "/api/invoicing/invoices/$id", version),
        Route(HttpMethod.POST, "/api/invoicing/invoices/$id/confirm", version),
        Route(HttpMethod.POST, "/api/invoicing/invoices/$id/discard", version),
        Route(HttpMethod.POST, "/api/invoicing/invoices/$id/recognize"),
        Route(HttpMethod.GET, "/api/invoicing/invoices/$id/original"),
        Route(HttpMethod.GET, "/api/invoicing/invoices/$id/history"),
        Route(HttpMethod.GET, "/api/invoicing/reports?period=YEARLY&year=2026"),
        Route(HttpMethod.GET, "/api/invoicing/quarters?year=2026"),
        Route(HttpMethod.POST, "/api/invoicing/quarters/2026/3/close"),
        Route(HttpMethod.POST, "/api/invoicing/quarters/2026/3/reopen", """{"reason":"Comprobacion de permisos"}""")
    )

    private fun send(r: Route, token: String?): Int {
        val request = client.method(r.method).uri(r.path)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
        when {
            r.multipart -> {
                val parts = LinkedMultiValueMap<String, Any>().apply {
                    add("files", object : ByteArrayResource("%PDF-1.7 x".toByteArray()) {
                        override fun getFilename() = "f.pdf"
                    })
                }
                request.contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
            }
            r.body != null -> request.contentType(MediaType.APPLICATION_JSON).body(r.body)
        }
        return request.exchange({ _, resp -> resp.statusCode.value() }, false)!!
    }

    private fun token(role: Role) = jwtService.generateAccessToken(UUID.randomUUID(), role)

    @Test
    fun `MANAGER, EMPLOYEE and REPRESENTATIVE are refused on every route`() {
        listOf(Role.MANAGER, Role.EMPLOYEE, Role.REPRESENTATIVE).forEach { role ->
            val t = token(role)
            routes.forEach { r -> assertEquals(403, send(r, t), "${r.method} ${r.path} as $role") }
        }
    }

    @Test
    fun `without a token, 401 on every route`() {
        routes.forEach { r -> assertEquals(401, send(r, null), "${r.method} ${r.path}") }
    }

    @Test
    fun `ADMIN gets past authorisation on every route`() {
        val t = token(Role.ADMIN)
        routes.forEach { r ->
            val status = send(r, t)
            assertTrue(status != 401 && status != 403, "${r.method} ${r.path} as ADMIN gave $status")
        }
    }
}
