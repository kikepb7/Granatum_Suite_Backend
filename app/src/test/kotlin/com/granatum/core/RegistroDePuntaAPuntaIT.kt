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
 * Feature 005 end to end, with the real `timetracking` behind the
 * `FichasPersonal` contract and the real `SecurityConfig` (SC-001, SC-002).
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

    @Test
    fun `a worker signs up, an ADMIN approves with the code and the worker clocks in with their own password`() {
        val admin = jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)
        val email = correo()
        val documento = dniValido()

        val alta = registro(email, documento)
        assertEquals(202, alta.estado, alta.cuerpo)
        val codigo = assertNotNull(campo(alta.cuerpo, "codigoVerificacion"))
        assertEquals(401, login(email).estado, "no access before approval")

        val lista = http.get("/api/auth/registros", admin)
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"[^}]*\"email\"\\s*:\\s*\"$email\"").find(lista.cuerpo)?.groupValues?.get(1)
        assertNotNull(id, lista.cuerpo)

        val aprobado = http.post(
            "/api/auth/registros/$id/aprobar",
            """{"codigoVerificacion":"$codigo","rol":"EMPLEADO","puesto":"Florista","tipoContrato":"PARCIAL","fechaAlta":"2026-10-01"}""",
            admin
        )
        assertEquals(201, aprobado.estado, aprobado.cuerpo)
        val empleadoId = assertNotNull(campo(aprobado.cuerpo, "empleadoId"))

        val sesion = login(email)
        assertEquals(200, sesion.estado, sesion.cuerpo)
        assertTrue(sesion.cuerpo.contains("\"requiereCambioPassword\":false"), sesion.cuerpo)

        val ficha = http.get("/api/empleados/$empleadoId", admin)
        assertEquals(200, ficha.estado)
        assertEquals("Florista", campo(ficha.cuerpo, "puesto"))
    }
}
