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
 * the DNI of self-employed suppliers. ENCARGADO runs inventory and approves
 * shifts, but has no business here. Checked through the real security chain,
 * for **every** route of contracts/README.md including the multipart upload,
 * so a route added without its rule fails here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AutorizacionFacturacionIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val cliente by lazy { RestClient.builder().baseUrl("http://localhost:$puerto").build() }

    private data class Ruta(val metodo: HttpMethod, val ruta: String, val cuerpo: Any? = null, val multipart: Boolean = false)

    private val id = UUID.randomUUID()
    private val version = """{"version":0}"""

    private val rutas = listOf(
        Ruta(HttpMethod.GET, "/api/facturacion/empresa"),
        Ruta(HttpMethod.PUT, "/api/facturacion/empresa", """{"razonSocial":"X","nif":"B12345674"}"""),
        Ruta(HttpMethod.POST, "/api/facturacion/facturas", multipart = true),
        Ruta(HttpMethod.GET, "/api/facturacion/facturas"),
        Ruta(HttpMethod.GET, "/api/facturacion/facturas/$id"),
        Ruta(HttpMethod.PUT, "/api/facturacion/facturas/$id", version),
        Ruta(HttpMethod.POST, "/api/facturacion/facturas/$id/confirmar", version),
        Ruta(HttpMethod.POST, "/api/facturacion/facturas/$id/descartar", version),
        Ruta(HttpMethod.POST, "/api/facturacion/facturas/$id/reconocer"),
        Ruta(HttpMethod.GET, "/api/facturacion/facturas/$id/original"),
        Ruta(HttpMethod.GET, "/api/facturacion/facturas/$id/historial"),
        Ruta(HttpMethod.GET, "/api/facturacion/reportes?periodo=ANUAL&anio=2026"),
        Ruta(HttpMethod.GET, "/api/facturacion/trimestres?anio=2026"),
        Ruta(HttpMethod.POST, "/api/facturacion/trimestres/2026/3/cerrar"),
        Ruta(HttpMethod.POST, "/api/facturacion/trimestres/2026/3/reabrir", """{"motivo":"Comprobacion de permisos"}""")
    )

    private fun pedir(r: Ruta, token: String?): Int {
        val peticion = cliente.method(r.metodo).uri(r.ruta)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
        when {
            r.multipart -> {
                val partes = LinkedMultiValueMap<String, Any>().apply {
                    add("ficheros", object : ByteArrayResource("%PDF-1.7 x".toByteArray()) {
                        override fun getFilename() = "f.pdf"
                    })
                }
                peticion.contentType(MediaType.MULTIPART_FORM_DATA).body(partes)
            }
            r.cuerpo != null -> peticion.contentType(MediaType.APPLICATION_JSON).body(r.cuerpo)
        }
        return peticion.exchange({ _, resp -> resp.statusCode.value() }, false)!!
    }

    private fun token(rol: Role) = jwtService.generateAccessToken(UUID.randomUUID(), rol)

    @Test
    fun `ENCARGADO, EMPLEADO and REPRESENTANTE are refused on every route`() {
        listOf(Role.ENCARGADO, Role.EMPLEADO, Role.REPRESENTANTE).forEach { rol ->
            val t = token(rol)
            rutas.forEach { r -> assertEquals(403, pedir(r, t), "${r.metodo} ${r.ruta} as $rol") }
        }
    }

    @Test
    fun `without a token, 401 on every route`() {
        rutas.forEach { r -> assertEquals(401, pedir(r, null), "${r.metodo} ${r.ruta}") }
    }

    @Test
    fun `ADMIN gets past authorisation on every route`() {
        val t = token(Role.ADMIN)
        rutas.forEach { r ->
            val estado = pedir(r, t)
            assertTrue(estado != 401 && estado != 403, "${r.metodo} ${r.ruta} as ADMIN gave $estado")
        }
    }
}
