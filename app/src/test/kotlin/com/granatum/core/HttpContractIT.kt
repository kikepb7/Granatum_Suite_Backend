package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The HTTP boundary, exercised with real JSON over a real socket.
 *
 * ## Why this exists
 *
 * Every other integration test in this project calls a service or a controller
 * **method** directly, with Kotlin objects. None of them crossed the HTTP
 * boundary, so none deserialised a request body - and that gap hid a complete
 * outage. Spring Boot 4's web layer uses Jackson 3 (`tools.jackson`), while the
 * project only carried the Kotlin module for Jackson 2
 * (`com.fasterxml.jackson`). Jackson could not use any data class's primary
 * constructor, so **every POST answered 500** while the whole suite stayed
 * green.
 *
 * It was found by walking the quickstart against the running application, which
 * is a slow way to learn something a test should say in a second.
 *
 * A real port rather than MockMvc, and not only because Boot 4 removed
 * `@AutoConfigureMockMvc` and `TestRestTemplate`: MockMvc never opens a socket,
 * and what is under test here is precisely the wire - the converters, the
 * filters and the JSON.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpContractIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private data class Response(val status: Int, val body: String)

    private val client: RestClient
        get() = RestClient.builder().baseUrl("http://localhost:$port").build()

    private fun token(role: Role, subject: UUID = UUID.randomUUID()) =
        "Bearer ${jwtService.generateAccessToken(subject, role)}"

    private fun post(path: String, auth: String, json: String): Response =
        client.post().uri(path)
            .header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON)
            .body(json)
            .exchange({ _, response ->
                Response(response.statusCode.value(), response.body.readAllBytes().decodeToString())
            }, false)!!

    private fun get(path: String, auth: String): Response =
        client.get().uri(path)
            .header("Authorization", auth)
            .exchange({ _, response ->
                Response(response.statusCode.value(), response.body.readAllBytes().decodeToString())
            }, false)!!

    /** Deliberately a regex and not a JSON parse: it reads the raw wire bytes. */
    private fun field(body: String, name: String): String? =
        Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)

    private fun validDni(): String {
        val number = (70_000_000..79_999_999).random()
        return "$number${"TRWAGMYFPDXBNJZSQVHLCKE"[number % 23]}"
    }

    private fun createEmployee(): UUID {
        val r = post(
            "/api/employees", token(Role.ADMIN),
            """
            {
              "name": "Persona de contrato",
              "lastName": "García López",
              "identityDocument": "${validDni()}",
              "position": "Florista",
              "contractType": "FULL_TIME",
              "hireDate": "2026-10-01"
            }
            """.trimIndent()
        )
        assertEquals(201, r.status, "setup failed: ${r.body.take(300)}")
        return UUID.fromString(assertNotNull(field(r.body, "id")))
    }

    private fun minutesAgo(n: Long): Instant =
        Instant.now().minus(n, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS)

    // --- Deserialisation ----------------------------------------------------

    @Test
    fun `a request body of a Kotlin data class deserialises`() {
        val dni = validDni()

        val r = post(
            "/api/employees", token(Role.ADMIN),
            """
            {
              "name": "Persona de contrato",
              "lastName": "García López",
              "identityDocument": "$dni",
              "position": "Florista",
              "contractType": "FULL_TIME",
              "hireDate": "2026-10-01"
            }
            """.trimIndent()
        )

        assertEquals(
            201, r.status,
            "a 500 here means Jackson cannot use the data class's primary " +
                "constructor: ${r.body.take(300)}"
        )
        assertNotNull(field(r.body, "id"))
        assertEquals(dni, field(r.body, "identityDocument"))
    }

    /**
     * Instants must travel as ISO-8601, not as epoch numbers. A client reading
     * `1760000000.123` where it expected a timestamp is a contract break no
     * Kotlin-to-Kotlin test would notice.
     */
    @Test
    fun `instants round-trip as ISO-8601 through the HTTP boundary`() {
        val employeeId = createEmployee()
        val occurredAt = minutesAgo(30)

        val r = post(
            "/api/shifts/clock-in", token(Role.EMPLOYEE, employeeId),
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"$occurredAt"}"""
        )

        assertEquals(201, r.status, r.body.take(300))
        assertEquals("IN_PROGRESS", field(r.body, "status"))
        assertEquals(
            occurredAt.toString(),
            field(r.body, "clockIn"),
            "the instant must come back as the string that was sent, neither as a " +
                "number nor shifted by a timezone conversion on the way through"
        )
    }

    @Test
    fun `an optional nested object deserialises when present`() {
        val employeeId = createEmployee()

        val r = post(
            "/api/shifts/clock-in", token(Role.EMPLOYEE, employeeId),
            """
            {
              "clientEventId": "${UUID.randomUUID()}",
              "occurredAt": "${minutesAgo(10)}",
              "location": { "latitude": 37.389100, "longitude": -5.984500, "accuracyMeters": 12 }
            }
            """.trimIndent()
        )

        assertEquals(201, r.status, r.body.take(300))
        assertTrue(
            r.body.contains("clockInLocation") && r.body.contains("37.389100"),
            "the nested object must survive the round trip: ${r.body.take(300)}"
        )
    }

    /** And its absence must not be an error (FR-009). */
    @Test
    fun `the optional nested object may be omitted entirely`() {
        val employeeId = createEmployee()

        val r = post(
            "/api/shifts/clock-in", token(Role.EMPLOYEE, employeeId),
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${minutesAgo(5)}"}"""
        )

        assertEquals(201, r.status, r.body.take(300))
    }

    // --- The error contract -------------------------------------------------

    @Test
    fun `an error comes back in the single declared shape`() {
        val r = get(
            "/api/shifts/employee/${UUID.randomUUID()}?from=2026-10-01&to=2026-10-31",
            token(Role.EMPLOYEE)
        )

        assertEquals(403, r.status)
        assertEquals("FORBIDDEN", field(r.body, "code"))
        assertNotNull(field(r.body, "message"))
    }

    /**
     * The module-specific handler must win over the generic one in `common`.
     * Both can handle these exceptions, and without the explicit `@Order` the
     * generic one could answer `400 INVALID_OPERATION` instead of the 409 and
     * the specific code the contract declares.
     */
    @Test
    fun `the module's error codes win over the generic ones`() {
        val employeeId = createEmployee()
        val auth = token(Role.EMPLOYEE, employeeId)

        assertEquals(
            201,
            post(
                "/api/shifts/clock-in", auth,
                """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${minutesAgo(20)}"}"""
            ).status
        )

        val second = post(
            "/api/shifts/clock-in", auth,
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${minutesAgo(15)}"}"""
        )

        assertEquals(409, second.status, second.body.take(300))
        assertEquals(
            "SHIFT_ALREADY_IN_PROGRESS",
            field(second.body, "code"),
            "INVALID_OPERATION here would mean the generic handler won"
        )
    }

    /** Clock skew has its own code so the app can explain it (FR-026b). */
    @Test
    fun `a future-dated operation is refused with its own code`() {
        val employeeId = createEmployee()

        val r = post(
            "/api/shifts/clock-in", token(Role.EMPLOYEE, employeeId),
            """
            {
              "clientEventId": "${UUID.randomUUID()}",
              "occurredAt": "${Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)}"
            }
            """.trimIndent()
        )

        assertEquals(422, r.status, r.body.take(300))
        assertEquals("CLOCK_SKEW", field(r.body, "code"))
    }

    // --- Idempotency on the wire --------------------------------------------

    /**
     * SC-005 through HTTP. The service-level tests construct the DTO in Kotlin;
     * this one proves the fingerprint is stable across two identical **JSON**
     * payloads, which is the only form a client actually sends.
     */
    @Test
    fun `resending the identical JSON returns the original response`() {
        val employeeId = createEmployee()
        val auth = token(Role.EMPLOYEE, employeeId)
        val body =
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${minutesAgo(25)}"}"""

        val first = post("/api/shifts/clock-in", auth, body)
        val second = post("/api/shifts/clock-in", auth, body)

        assertEquals(201, first.status)
        assertEquals(
            field(first.body, "id"),
            field(second.body, "id"),
            "a 409 here would mean the retry was treated as a fresh clock-in"
        )
        assertEquals(first.body, second.body, "verbatim, not merely equivalent")
    }

    // --- FR-020a on the wire ------------------------------------------------

    @Test
    fun `a correction carrying a location is refused rather than ignored`() {
        val employeeId = createEmployee()
        val auth = token(Role.EMPLOYEE, employeeId)
        val clockIn = minutesAgo(540)
        val clockOut = minutesAgo(60)

        val created = post(
            "/api/shifts/clock-in", auth,
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"$clockIn"}"""
        )
        assertEquals(201, created.status, created.body.take(300))
        val shiftId = assertNotNull(field(created.body, "id"))

        assertEquals(
            200,
            post(
                "/api/shifts/$shiftId/clock-out", auth,
                """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"$clockOut"}"""
            ).status
        )

        val r = post(
            "/api/shifts/$shiftId/corrections", auth,
            """
            {
              "reason": "Quiero cambiar donde fiche",
              "proposedValues": {
                "clockIn": "$clockIn",
                "clockOut": "$clockOut",
                "breaks": [],
                "location": { "latitude": 37.0, "longitude": -5.0 }
              }
            }
            """.trimIndent()
        )

        assertEquals(
            422, r.status,
            "a 201 here would mean the location was silently ignored and the client " +
                "believes it corrected something it did not: ${r.body.take(300)}"
        )
        assertEquals("LOCATION_NOT_CORRECTABLE", field(r.body, "code"))
    }

    // --- REPRESENTATIVE on the wire ----------------------------------------

    @Test
    fun `a REPRESENTATIVE reads the register without locations and writes nothing`() {
        val employeeId = createEmployee()
        val auth = token(Role.EMPLOYEE, employeeId)

        assertEquals(
            201,
            post(
                "/api/shifts/clock-in", auth,
                """
                {
                  "clientEventId": "${UUID.randomUUID()}",
                  "occurredAt": "${minutesAgo(40)}",
                  "location": { "latitude": 37.389100, "longitude": -5.984500 }
                }
                """.trimIndent()
            ).status
        )

        val representative = token(Role.REPRESENTATIVE)
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Madrid"))

        // From yesterday: the shift started 40 minutes ago, which in the first
        // 40 minutes after midnight is still yesterday, and a today-only range
        // made this test fail every night between 00:00 and 00:40.
        val reading = get(
            "/api/shifts/employee/$employeeId?from=${today.minusDays(1)}&to=$today",
            representative
        )
        assertEquals(200, reading.status, reading.body.take(300))
        assertTrue(reading.body.contains("\"status\""), "must actually return the register")
        assertTrue(
            !reading.body.contains("37.389100"),
            "article 34.9 entitles representatives to the register, not to where " +
                "each person was: ${reading.body.take(300)}"
        )

        assertEquals(
            403,
            post(
                "/api/shifts/clock-in", representative,
                """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${minutesAgo(5)}"}"""
            ).status
        )
        assertEquals(403, post("/api/employees", representative, "{}").status)
    }

    // --- auth ---------------------------------------------------------------

    /**
     * The new routes cross the same boundary the Jackson 3 outage broke, so
     * they get the same guard. No account is needed: a well-formed body for an
     * unknown address must deserialise and come back as the stable 401, and a
     * converter that could not build `LoginRequest` would answer 500 instead.
     */
    @Test
    fun `the login body deserialises through the real chain`() {
        val r = client.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"email":"nobody-${UUID.randomUUID()}@granatum.es","password":"Granatum-2026!"}""")
            .exchange({ _, response ->
                Response(response.statusCode.value(), response.body.readAllBytes().decodeToString())
            }, false)!!

        assertEquals(401, r.status, "a 500 here is the Jackson outage again: ${r.body}")
        assertEquals("INVALID_CREDENTIALS", field(r.body, "code"))
    }
}
