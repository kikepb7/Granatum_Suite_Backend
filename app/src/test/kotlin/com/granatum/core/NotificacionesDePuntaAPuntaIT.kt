package com.granatum.core

import com.granatum.core.ClientePruebaHttp.Companion.campo
import com.granatum.core.ClientePruebaHttp.Companion.dniValido
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Feature 008 end to end, with every real piece: `absences` publishes,
 * `notifications` routes through `auth`'s DirectorioRoles, and the JSON is the
 * one the server actually writes (SC-001, SC-004).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificacionesDePuntaAPuntaIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired lateinit var jwtService: JwtService

    private val http by lazy { ClientePruebaHttp(puerto) }
    private val admin by lazy { jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN) }

    private fun persona(): UUID {
        val alta = http.post(
            "/api/empleados",
            """{"nombre":"Persona Avisos","documentoIdentidad":"${dniValido()}","puesto":"Florista","tipoContrato":"PARCIAL","fechaAlta":"2026-01-01"}""",
            admin
        )
        assertEquals(201, alta.estado, alta.cuerpo)
        return UUID.fromString(campo(alta.cuerpo, "id"))
    }

    private fun conCuenta(empleadoId: UUID, rol: Role) {
        val cuenta = http.post(
            "/api/auth/cuentas",
            """{"empleadoId":"$empleadoId","email":"p-${UUID.randomUUID()}@granatum.es","rol":"$rol"}""",
            admin
        )
        assertEquals(201, cuenta.estado, cuenta.cuerpo)
    }

    @Test
    fun `an absence request reaches the ENCARGADO and its approval reaches the person`() {
        val empleadoId = persona()
        val encargadoId = persona().also { conCuenta(it, Role.ENCARGADO) }
        val empleado = jwtService.generateAccessToken(empleadoId, Role.EMPLEADO)
        val encargado = jwtService.generateAccessToken(encargadoId, Role.ENCARGADO)
        val anio = LocalDate.now().year + 1

        val pedida = http.post("/api/ausencias", """{"tipo":"VACACIONES","desde":"$anio-04-06","hasta":"$anio-04-08"}""", empleado)
        assertEquals(201, pedida.estado, pedida.cuerpo)
        val ausenciaId = assertNotNull(campo(pedida.cuerpo, "id"))

        val bandejaEncargado = http.get("/api/notificaciones?soloNoLeidas=true", encargado)
        assertEquals(200, bandejaEncargado.estado)
        assertTrue(
            bandejaEncargado.cuerpo.contains("\"tipo\":\"AUSENCIA_PENDIENTE\",\"referenciaId\":\"$ausenciaId\""),
            bandejaEncargado.cuerpo
        )
        assertTrue(bandejaEncargado.cuerpo.contains("\"leidaEn\":null"), "the unread mark travels as an explicit null")

        assertEquals(200, http.post("/api/ausencias/$ausenciaId/aprobar", "{}", encargado).estado)

        val bandejaPersona = http.get("/api/notificaciones", empleado)
        assertTrue(
            bandejaPersona.cuerpo.contains("\"tipo\":\"AUSENCIA_APROBADA\",\"referenciaId\":\"$ausenciaId\""),
            bandejaPersona.cuerpo
        )
        assertEquals(false, bandejaPersona.cuerpo.contains("AUSENCIA_PENDIENTE"), "nobody is told about their own request")
    }

    @Test
    fun `nobody marks someone else's notice, and the inbox needs a token`() {
        val empleadoId = persona()
        val encargadoId = persona().also { conCuenta(it, Role.ENCARGADO) }
        val anio = LocalDate.now().year + 1
        http.post(
            "/api/ausencias", """{"tipo":"VACACIONES","desde":"$anio-05-04","hasta":"$anio-05-05"}""",
            jwtService.generateAccessToken(empleadoId, Role.EMPLEADO)
        )
        val encargado = jwtService.generateAccessToken(encargadoId, Role.ENCARGADO)
        val idAviso = assertNotNull(campo(http.get("/api/notificaciones", encargado).cuerpo, "id"))

        val intruso = jwtService.generateAccessToken(UUID.randomUUID(), Role.EMPLEADO)
        assertEquals(404, http.post("/api/notificaciones/$idAviso/leida", "{}", intruso).estado)
        assertEquals(401, http.get("/api/notificaciones").estado)
        assertEquals(200, http.get("/api/notificaciones", jwtService.generateAccessToken(UUID.randomUUID(), Role.REPRESENTANTE)).estado)
        assertEquals(204, http.post("/api/notificaciones/$idAviso/leida", "{}", encargado).estado)
    }
}
