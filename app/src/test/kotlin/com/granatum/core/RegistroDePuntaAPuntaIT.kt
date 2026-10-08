package com.granatum.core

import com.granatum.core.ClientePruebaHttp.Companion.campo
import com.granatum.core.ClientePruebaHttp.Companion.dniValido
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sign-up of the owner (feature 005 US1) and onboarding by an ADMIN (feature
 * 009) end to end, with the real `timetracking` behind the `FichasPersonal`
 * contract and the real `SecurityConfig`.
 *
 * ## The one piece of setup that touches other tests' data
 *
 * The bootstrap only works while no ADMIN account exists, and this database is
 * shared with the other classes in `app`. So the bootstrap test demotes the
 * existing ADMIN accounts first and **restores them** afterwards, by id - never
 * deleting anything, since cuentas_acceso has no delete path anywhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["auth.registro.codigo-arranque=${RegistroDePuntaAPuntaIT.CODIGO}"])
class RegistroDePuntaAPuntaIT {

    companion object {
        const val CODIGO = "arranque-punta-a-punta-0123456789"
    }

    @LocalServerPort
    var puerto: Int = 0

    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var jdbc: JdbcTemplate

    private val http by lazy { ClientePruebaHttp(puerto) }
    private val password = "Granatum-Punta-2026!"

    private fun correo() = "p-${UUID.randomUUID()}@granatum.es"

    private fun registro(email: String, documento: String, codigoArranque: String? = null) =
        http.post(
            "/api/auth/registro",
            """{"email":"$email","password":"$password","nombre":"Persona de prueba","documentoIdentidad":"$documento"""" +
                (codigoArranque?.let { ""","codigoArranque":"$it"""" } ?: "") + "}"
        )

    private fun login(email: String) =
        http.post("/api/auth/login", """{"email":"$email","password":"$password"}""")

    @Test
    fun `the first ADMIN signs up with the bootstrap code and manages staff straight away`() {
        val admins = jdbc.queryForList("SELECT id FROM cuentas_acceso WHERE rol = 'ADMIN'", UUID::class.java)
        jdbc.update("UPDATE cuentas_acceso SET rol = 'EMPLEADO' WHERE rol = 'ADMIN'")
        try {
            val email = correo()

            val alta = registro(email, dniValido(), CODIGO)
            assertEquals(201, alta.estado, alta.cuerpo)

            val sesion = login(email)
            assertEquals(200, sesion.estado, sesion.cuerpo)
            val token = assertNotNull(campo(sesion.cuerpo, "accessToken"))
            assertEquals(200, http.get("/api/empleados", token).estado, "an ADMIN manages staff")

            assertEquals(403, registro(correo(), dniValido(), CODIGO).estado, "the code is spent")
        } finally {
            if (admins.isNotEmpty()) {
                jdbc.update(
                    "UPDATE cuentas_acceso SET rol = 'ADMIN' WHERE id IN (${admins.joinToString { "'$it'" }})"
                )
            }
        }
    }

    /** Feature 009, FR-001: only the owner signs up; without the code nothing is stored. */
    @Test
    fun `signing up without the bootstrap code is refused and stores nothing`() {
        val email = correo()

        val respuesta = registro(email, dniValido())

        assertEquals(400, respuesta.estado, respuesta.cuerpo)
        assertTrue(respuesta.cuerpo.contains("\"code\":\"VALIDACION\""), respuesta.cuerpo)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cuentas_acceso WHERE email = ?", Int::class.java, email))
    }

    /**
     * Feature 009, US2 end to end: the ADMIN onboards a person in one call with
     * the real timetracking behind FichasPersonal, hands over the temporary
     * password, and the person can do nothing but change it - then clocks in.
     */
    @Test
    fun `an ADMIN onboards a worker, who changes the temporary password and clocks in`() {
        val admin = jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)
        val email = correo()

        val alta = http.post(
            "/api/auth/altas",
            """{"nombre":"Persona de prueba","documentoIdentidad":"${dniValido()}","puesto":"Florista","tipoContrato":"PARCIAL","fechaAlta":"2026-10-01","email":"$email","rol":"EMPLEADO"}""",
            admin
        )
        assertEquals(201, alta.estado, alta.cuerpo)
        val temporal = assertNotNull(campo(alta.cuerpo, "passwordTemporal"))
        val empleadoId = assertNotNull(campo(alta.cuerpo, "empleadoId"))
        assertEquals("Florista", campo(http.get("/api/empleados/$empleadoId", admin).cuerpo, "puesto"))

        val primera = http.post("/api/auth/login", """{"email":"$email","password":"$temporal"}""")
        assertEquals(200, primera.estado, primera.cuerpo)
        assertTrue(primera.cuerpo.contains("\"requiereCambioPassword\":true"), primera.cuerpo)
        val provisional = assertNotNull(campo(primera.cuerpo, "accessToken"))
        val entrada = """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${ClientePruebaHttp.haceMinutos(5)}"}"""
        assertEquals(403, http.post("/api/fichajes/entrada", entrada, provisional).estado, "only the password change until it is done")

        val cambio = http.post(
            "/api/auth/change-password",
            """{"passwordActual":"$temporal","passwordNueva":"$password"}""",
            provisional
        )
        assertEquals(200, cambio.estado, cambio.cuerpo)
        val definitivo = assertNotNull(campo(cambio.cuerpo, "accessToken"))

        assertEquals(201, http.post("/api/fichajes/entrada", entrada, definitivo).estado)
        assertEquals(200, login(email).estado, "and the person's own password works from now on")
    }
}
