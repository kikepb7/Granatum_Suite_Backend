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
 * `available` field. Authorisation is `app`'s (AutorizacionEventosIT), as in every module.
 */
@SpringBootTest(classes = [EventsTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PermissiveSecurityTestConfig::class)
class EventsHttpIT : BaseEventsIT() {

    @LocalServerPort
    var port: Int = 0

    @Autowired lateinit var jwt: JwtService

    private data class Response(val status: Int, val body: String)

    private val token get() = jwt.generateAccessToken(UUID.randomUUID(), Role.EMPLOYEE)
    private val tomorrow get() = LocalDate.now(ZoneId.of("Europe/Madrid")).plusDays(1)

    private fun send(method: HttpMethod, path: String, json: String? = null): Response =
        RestClient.create("http://localhost:$port").method(method).uri(path)
            .header("Authorization", "Bearer $token")
            .apply { if (json != null) contentType(MediaType.APPLICATION_JSON).body(json) }
            .exchange({ _, r -> Response(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!

    private fun field(body: String, name: String): String? =
        Regex("\"$name\"\\s*:\\s*\"?([^\",}]*)\"?").find(body)?.groupValues?.get(1)

    private fun create(): String {
        val response = send(HttpMethod.POST, "/api/events", """{"name":"Evento 1","description":"Boda de Juan y María","date":"$tomorrow"}""")
        assertEquals(expected = 201, actual = response.status, message = response.body)
        return field(response.body, "id")!!
    }

    @Test
    fun `When an event is created then it comes back scheduled with its date`() {
        val id = create()

        val actual = send(HttpMethod.GET, "/api/events/$id")

        assertEquals(expected = 200, actual = actual.status)
        assertEquals(expected = "SCHEDULED", actual = field(actual.body, "status"))
        assertEquals(expected = tomorrow.toString(), actual = field(actual.body, "date"))
    }

    @Test
    fun `When more units are asked for than there are then the answer says how many there are`() {
        val id = create()
        val material = inventory.material(quantity = 3)

        val actual = send(HttpMethod.PUT, "/api/events/$id/materials/$material", """{"quantity":4}""")

        assertEquals(expected = 409, actual = actual.status)
        assertEquals(expected = "INSUFFICIENT_STOCK", actual = field(actual.body, "code"))
        assertEquals(expected = "3", actual = field(actual.body, "available"))
    }

    @Test
    fun `When the request breaks the rules then each answer has its code`() {
        val id = create()

        val past = send(HttpMethod.POST, "/api/events", """{"name":"Evento 1","date":"2000-01-01"}""")
        val noName = send(HttpMethod.POST, "/api/events", """{"name":" ","date":"$tomorrow"}""")
        val zero = send(HttpMethod.PUT, "/api/events/$id/materials/${UUID.randomUUID()}", """{"quantity":0}""")
        val missing = send(HttpMethod.PUT, "/api/events/$id/materials/${UUID.randomUUID()}", """{"quantity":1}""")
        val noEvent = send(HttpMethod.GET, "/api/events/${UUID.randomUUID()}")

        assertEquals(expected = 400 to "DATE_IN_PAST", actual = past.status to field(past.body, "code"))
        assertEquals(expected = 400 to "VALIDATION", actual = noName.status to field(noName.body, "code"))
        assertEquals(expected = 400 to "VALIDATION", actual = zero.status to field(zero.body, "code"))
        assertEquals(expected = 404 to "MATERIAL_NOT_FOUND", actual = missing.status to field(missing.body, "code"))
        assertEquals(expected = 404 to "EVENT_NOT_FOUND", actual = noEvent.status to field(noEvent.body, "code"))
    }

    @Test
    fun `When a finished event is changed then it is refused, and finishing again is harmless`() {
        val id = create()
        val material = inventory.material(quantity = 5)

        assertEquals(expected = 200, actual = send(HttpMethod.POST, "/api/events/$id/finish").status)
        val again = send(HttpMethod.POST, "/api/events/$id/finish")
        val change = send(HttpMethod.PUT, "/api/events/$id/materials/$material", """{"quantity":1}""")

        assertEquals(expected = 200, actual = again.status)
        assertEquals(expected = 409 to "EVENT_FINISHED", actual = change.status to field(change.body, "code"))
    }

    @Test
    fun `When the picker is opened then it lists materials with their free units`() {
        inventory.material(quantity = 7, description = "Cinta de raso", category = "Cintas")

        val actual = send(HttpMethod.GET, "/api/events/materials")

        assertEquals(expected = 200, actual = actual.status)
        assertTrue("\"description\":\"Cinta de raso\"" in actual.body && "\"warehouse\":\"A\"" in actual.body, actual.body)
    }
}
