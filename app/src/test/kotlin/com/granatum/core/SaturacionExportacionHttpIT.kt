package com.granatum.core

import com.granatum.core.ClientePruebaHttp.Companion.campo
import com.granatum.core.ClientePruebaHttp.Companion.dniValido
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
 * `503 EXPORTACION_SATURADA` with `Retry-After`, decided before any byte of the
 * file - never a `200` that breaks off.
 *
 * Zero permits, so every export is saturated without having to hold others
 * open; `SaturacionExportacionIT` in `timetracking` covers the permit
 * accounting itself.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "timetracking.exportacion.concurrencia=0",
        "timetracking.exportacion.espera-ms=10"
    ]
)
class SaturacionExportacionHttpIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    @Test
    fun `a saturated export answers 503 with Retry-After`() {
        // A real person, so that validation passes and only saturation can refuse.
        val alta = ClientePruebaHttp(puerto).post(
            "/api/empleados",
            """{"nombre":"Persona Saturada","documentoIdentidad":"${dniValido()}",
                "puesto":"Florista","tipoContrato":"JORNADA_COMPLETA","fechaAlta":"2026-10-01"}""",
            jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)
        )
        assertEquals(201, alta.estado, alta.cuerpo)
        val id = UUID.fromString(campo(alta.cuerpo, "id")!!)

        val (estado, reintentar, cuerpo) = RestClient.builder().baseUrl("http://localhost:$puerto").build()
            .get().uri("/api/fichajes/export?desde=2026-10-01&hasta=2026-10-31")
            .header("Authorization", "Bearer ${jwtService.generateAccessToken(id, Role.EMPLEADO)}")
            .exchange({ _, r ->
                Triple(r.statusCode.value(), r.headers.getFirst("Retry-After"), r.body.readAllBytes().decodeToString())
            }, false)!!

        assertEquals(503, estado, cuerpo)
        assertEquals("1", reintentar)
        assertEquals("EXPORTACION_SATURADA", campo(cuerpo, "code"))
    }
}
