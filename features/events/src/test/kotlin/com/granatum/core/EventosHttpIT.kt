package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The HTTP contract of feature 011 over a real server: status codes, error codes and the extra
 * `disponible` field. Authorisation is `app`'s (AutorizacionEventosIT), as in every module.
 */
@SpringBootTest(classes = [EventsTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SeguridadPermisivaTestConfig::class)
class EventosHttpIT : BaseEventosIT() {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired lateinit var jwt: JwtService

    private data class Respuesta(val estado: Int, val cuerpo: String)

    private val token get() = jwt.generateAccessToken(UUID.randomUUID(), Role.EMPLEADO)
    private val manana get() = LocalDate.now(ZoneId.of("Europe/Madrid")).plusDays(1)

    private fun enviar(metodo: HttpMethod, ruta: String, json: String? = null): Respuesta =
        RestClient.create("http://localhost:$puerto").method(metodo).uri(ruta)
            .header("Authorization", "Bearer $token")
            .apply { if (json != null) contentType(MediaType.APPLICATION_JSON).body(json) }
            .exchange({ _, r -> Respuesta(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!

    private fun campo(cuerpo: String, nombre: String): String? =
        Regex("\"$nombre\"\\s*:\\s*\"?([^\",}]*)\"?").find(cuerpo)?.groupValues?.get(1)

    private fun crear(): String {
        val respuesta = enviar(HttpMethod.POST, "/api/eventos", """{"nombre":"Evento 1","descripcion":"Boda de Juan y María","fecha":"$manana"}""")
        assertEquals(expected = 201, actual = respuesta.estado, message = respuesta.cuerpo)
        return campo(respuesta.cuerpo, "id")!!
    }

    @Test
    fun `When an event is created then it comes back scheduled with its date`() {
        val id = crear()

        val actual = enviar(HttpMethod.GET, "/api/eventos/$id")

        assertEquals(expected = 200, actual = actual.estado)
        assertEquals(expected = "PROGRAMADO", actual = campo(actual.cuerpo, "estado"))
        assertEquals(expected = manana.toString(), actual = campo(actual.cuerpo, "fecha"))
    }

    @Test
    fun `When more units are asked for than there are then the answer says how many there are`() {
        val id = crear()
        val material = stock.material(cantidad = 3)

        val actual = enviar(HttpMethod.PUT, "/api/eventos/$id/materiales/$material", """{"cantidad":4}""")

        assertEquals(expected = 409, actual = actual.estado)
        assertEquals(expected = "STOCK_INSUFICIENTE", actual = campo(actual.cuerpo, "code"))
        assertEquals(expected = "3", actual = campo(actual.cuerpo, "disponible"))
    }

    @Test
    fun `When the request breaks the rules then each answer has its code`() {
        val id = crear()

        val pasada = enviar(HttpMethod.POST, "/api/eventos", """{"nombre":"Evento 1","fecha":"2000-01-01"}""")
        val sinNombre = enviar(HttpMethod.POST, "/api/eventos", """{"nombre":" ","fecha":"$manana"}""")
        val cero = enviar(HttpMethod.PUT, "/api/eventos/$id/materiales/${UUID.randomUUID()}", """{"cantidad":0}""")
        val inexistente = enviar(HttpMethod.PUT, "/api/eventos/$id/materiales/${UUID.randomUUID()}", """{"cantidad":1}""")
        val sinEvento = enviar(HttpMethod.GET, "/api/eventos/${UUID.randomUUID()}")

        assertEquals(expected = 400 to "FECHA_PASADA", actual = pasada.estado to campo(pasada.cuerpo, "code"))
        assertEquals(expected = 400 to "VALIDACION", actual = sinNombre.estado to campo(sinNombre.cuerpo, "code"))
        assertEquals(expected = 400 to "VALIDACION", actual = cero.estado to campo(cero.cuerpo, "code"))
        assertEquals(expected = 404 to "MATERIAL_NOT_FOUND", actual = inexistente.estado to campo(inexistente.cuerpo, "code"))
        assertEquals(expected = 404 to "EVENTO_NOT_FOUND", actual = sinEvento.estado to campo(sinEvento.cuerpo, "code"))
    }

    @Test
    fun `When a finished event is changed then it is refused, and finishing again is harmless`() {
        val id = crear()
        val material = stock.material(cantidad = 5)

        assertEquals(expected = 200, actual = enviar(HttpMethod.POST, "/api/eventos/$id/finalizar").estado)
        val otraVez = enviar(HttpMethod.POST, "/api/eventos/$id/finalizar")
        val cambio = enviar(HttpMethod.PUT, "/api/eventos/$id/materiales/$material", """{"cantidad":1}""")

        assertEquals(expected = 200, actual = otraVez.estado)
        assertEquals(expected = 409 to "EVENTO_FINALIZADO", actual = cambio.estado to campo(cambio.cuerpo, "code"))
    }

    @Test
    fun `When the picker is opened then it lists materials with their free units`() {
        stock.material(cantidad = 7, descripcion = "Cinta de raso", categoria = "Cintas")

        val actual = enviar(HttpMethod.GET, "/api/eventos/materiales")

        assertEquals(expected = 200, actual = actual.estado)
        assertTrue("\"descripcion\":\"Cinta de raso\"" in actual.cuerpo && "\"almacen\":\"A\"" in actual.cuerpo, actual.cuerpo)
    }
}
