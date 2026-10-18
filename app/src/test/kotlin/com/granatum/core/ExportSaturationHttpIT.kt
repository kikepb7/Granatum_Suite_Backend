package com.granatum.core

import com.granatum.core.HttpTestClient.Companion.field
import com.granatum.core.HttpTestClient.Companion.validDni
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals

/**
 * D-002 over HTTP: when every export slot is taken the answer is
 * `503 EXPORT_BUSY` with `Retry-After`, decided before any byte of the
 * file - never a `200` that breaks off.
 *
 * Zero permits, so every export is saturated without having to hold others
 * open; `RegisterExportSaturationIT` in `timetracking` covers the permit
 * accounting itself.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "timetracking.export.concurrency=0",
        "timetracking.export.wait-ms=10"
    ]
)
class ExportSaturationHttpIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    @Test
    fun `a saturated export answers 503 with Retry-After`() {
        // A real person, so that validation passes and only saturation can refuse.
        val created = HttpTestClient(port).post(
            "/api/employees",
            """{"name":"Persona","lastName":"Saturada","identityDocument":"${validDni()}",
                "position":"Florista","contractType":"FULL_TIME","hireDate":"2026-10-01"}""",
            jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)
        )
        assertEquals(201, created.status, created.body)
        val id = UUID.fromString(field(created.body, "id")!!)

        val (status, retryAfter, body) = RestClient.builder().baseUrl("http://localhost:$port").build()
            .get().uri("/api/shifts/export?from=2026-10-01&to=2026-10-31")
            .header("Authorization", "Bearer ${jwtService.generateAccessToken(id, Role.EMPLOYEE)}")
            .exchange({ _, r ->
                Triple(r.statusCode.value(), r.headers.getFirst("Retry-After"), r.body.readAllBytes().decodeToString())
            }, false)!!

        assertEquals(503, status, body)
        assertEquals("1", retryAfter)
        assertEquals("EXPORT_BUSY", field(body, "code"))
    }
}
