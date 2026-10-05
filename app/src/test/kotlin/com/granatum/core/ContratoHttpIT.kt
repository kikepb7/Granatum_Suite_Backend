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
class ContratoHttpIT {

    @LocalServerPort
    var puerto: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private data class Respuesta(val estado: Int, val cuerpo: String)

    private val cliente: RestClient
        get() = RestClient.builder().baseUrl("http://localhost:$puerto").build()

    private fun token(rol: Role, subject: UUID = UUID.randomUUID()) =
        "Bearer ${jwtService.generateAccessToken(subject, rol)}"

    private fun post(ruta: String, auth: String, json: String): Respuesta =
        cliente.post().uri(ruta)
            .header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON)
            .body(json)
            .exchange({ _, response ->
                Respuesta(response.statusCode.value(), response.body.readAllBytes().decodeToString())
            }, false)!!

    private fun get(ruta: String, auth: String): Respuesta =
        cliente.get().uri(ruta)
            .header("Authorization", auth)
            .exchange({ _, response ->
                Respuesta(response.statusCode.value(), response.body.readAllBytes().decodeToString())
            }, false)!!

    /** Deliberately a regex and not a JSON parse: it reads the raw wire bytes. */
    private fun campo(cuerpo: String, nombre: String): String? =
        Regex("\"$nombre\"\\s*:\\s*\"([^\"]*)\"").find(cuerpo)?.groupValues?.get(1)

    private fun dniValido(): String {
        val numero = (70_000_000..79_999_999).random()
        return "$numero${"TRWAGMYFPDXBNJZSQVHLCKE"[numero % 23]}"
    }

    private fun crearEmpleado(): UUID {
        val r = post(
            "/api/empleados", token(Role.ADMIN),
            """
            {
              "nombre": "Persona de contrato",
              "documentoIdentidad": "${dniValido()}",
              "puesto": "Florista",
              "tipoContrato": "JORNADA_COMPLETA",
              "fechaAlta": "2026-10-01"
            }
            """.trimIndent()
        )
        assertEquals(201, r.estado, "setup failed: ${r.cuerpo.take(300)}")
        return UUID.fromString(assertNotNull(campo(r.cuerpo, "id")))
    }

    private fun haceMinutos(n: Long): Instant =
        Instant.now().minus(n, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS)

    // --- Deserialisation ----------------------------------------------------

    @Test
    fun `a request body of a Kotlin data class deserialises`() {
        val dni = dniValido()

        val r = post(
            "/api/empleados", token(Role.ADMIN),
            """
            {
              "nombre": "Persona de contrato",
              "documentoIdentidad": "$dni",
              "puesto": "Florista",
              "tipoContrato": "JORNADA_COMPLETA",
              "fechaAlta": "2026-10-01"
            }
            """.trimIndent()
        )

        assertEquals(
            201, r.estado,
            "a 500 here means Jackson cannot use the data class's primary " +
                "constructor: ${r.cuerpo.take(300)}"
        )
        assertNotNull(campo(r.cuerpo, "id"))
        assertEquals(dni, campo(r.cuerpo, "documentoIdentidad"))
    }

    /**
     * Instants must travel as ISO-8601, not as epoch numbers. A client reading
     * `1760000000.123` where it expected a timestamp is a contract break no
     * Kotlin-to-Kotlin test would notice.
     */
    @Test
    fun `instants round-trip as ISO-8601 through the HTTP boundary`() {
        val empleadoId = crearEmpleado()
        val occurredAt = haceMinutos(30)

        val r = post(
            "/api/fichajes/entrada", token(Role.EMPLEADO, empleadoId),
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"$occurredAt"}"""
        )

        assertEquals(201, r.estado, r.cuerpo.take(300))
        assertEquals("EN_CURSO", campo(r.cuerpo, "estado"))
        assertEquals(
            occurredAt.toString(),
            campo(r.cuerpo, "entrada"),
            "the instant must come back as the string that was sent, neither as a " +
                "number nor shifted by a timezone conversion on the way through"
        )
    }

    @Test
    fun `an optional nested object deserialises when present`() {
        val empleadoId = crearEmpleado()

        val r = post(
            "/api/fichajes/entrada", token(Role.EMPLEADO, empleadoId),
            """
            {
              "clientEventId": "${UUID.randomUUID()}",
              "occurredAt": "${haceMinutos(10)}",
              "ubicacion": { "latitud": 37.389100, "longitud": -5.984500, "precisionMetros": 12 }
            }
            """.trimIndent()
        )

        assertEquals(201, r.estado, r.cuerpo.take(300))
        assertTrue(
            r.cuerpo.contains("ubicacionEntrada") && r.cuerpo.contains("37.389100"),
            "the nested object must survive the round trip: ${r.cuerpo.take(300)}"
        )
    }

    /** And its absence must not be an error (FR-009). */
    @Test
    fun `the optional nested object may be omitted entirely`() {
        val empleadoId = crearEmpleado()

        val r = post(
            "/api/fichajes/entrada", token(Role.EMPLEADO, empleadoId),
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${haceMinutos(5)}"}"""
        )

        assertEquals(201, r.estado, r.cuerpo.take(300))
    }

    // --- The error contract -------------------------------------------------

    @Test
    fun `an error comes back in the single declared shape`() {
        val r = get(
            "/api/fichajes/empleado/${UUID.randomUUID()}?desde=2026-10-01&hasta=2026-10-31",
            token(Role.EMPLEADO)
        )

        assertEquals(403, r.estado)
        assertEquals("FORBIDDEN", campo(r.cuerpo, "code"))
        assertNotNull(campo(r.cuerpo, "message"))
    }

    /**
     * The module-specific handler must win over the generic one in `common`.
     * Both can handle these exceptions, and without the explicit `@Order` the
     * generic one could answer `400 INVALID_OPERATION` instead of the 409 and
     * the specific code the contract declares.
     */
    @Test
    fun `the module's error codes win over the generic ones`() {
        val empleadoId = crearEmpleado()
        val auth = token(Role.EMPLEADO, empleadoId)

        assertEquals(
            201,
            post(
                "/api/fichajes/entrada", auth,
                """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${haceMinutos(20)}"}"""
            ).estado
        )

        val segunda = post(
            "/api/fichajes/entrada", auth,
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${haceMinutos(15)}"}"""
        )

        assertEquals(409, segunda.estado, segunda.cuerpo.take(300))
        assertEquals(
            "FICHAJE_YA_EN_CURSO",
            campo(segunda.cuerpo, "code"),
            "INVALID_OPERATION here would mean the generic handler won"
        )
    }

    /** Clock skew has its own code so the app can explain it (FR-026b). */
    @Test
    fun `a future-dated operation is refused with its own code`() {
        val empleadoId = crearEmpleado()

        val r = post(
            "/api/fichajes/entrada", token(Role.EMPLEADO, empleadoId),
            """
            {
              "clientEventId": "${UUID.randomUUID()}",
              "occurredAt": "${Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)}"
            }
            """.trimIndent()
        )

        assertEquals(422, r.estado, r.cuerpo.take(300))
        assertEquals("DESVIACION_RELOJ", campo(r.cuerpo, "code"))
    }

    // --- Idempotency on the wire --------------------------------------------

    /**
     * SC-005 through HTTP. The service-level tests construct the DTO in Kotlin;
     * this one proves the fingerprint is stable across two identical **JSON**
     * payloads, which is the only form a client actually sends.
     */
    @Test
    fun `resending the identical JSON returns the original response`() {
        val empleadoId = crearEmpleado()
        val auth = token(Role.EMPLEADO, empleadoId)
        val cuerpo =
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${haceMinutos(25)}"}"""

        val primera = post("/api/fichajes/entrada", auth, cuerpo)
        val segunda = post("/api/fichajes/entrada", auth, cuerpo)

        assertEquals(201, primera.estado)
        assertEquals(
            campo(primera.cuerpo, "id"),
            campo(segunda.cuerpo, "id"),
            "a 409 here would mean the retry was treated as a fresh clock-in"
        )
        assertEquals(primera.cuerpo, segunda.cuerpo, "verbatim, not merely equivalent")
    }

    // --- FR-020a on the wire ------------------------------------------------

    @Test
    fun `a correction carrying a location is refused rather than ignored`() {
        val empleadoId = crearEmpleado()
        val auth = token(Role.EMPLEADO, empleadoId)
        val entrada = haceMinutos(540)
        val salida = haceMinutos(60)

        val creado = post(
            "/api/fichajes/entrada", auth,
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"$entrada"}"""
        )
        assertEquals(201, creado.estado, creado.cuerpo.take(300))
        val fichajeId = assertNotNull(campo(creado.cuerpo, "id"))

        assertEquals(
            200,
            post(
                "/api/fichajes/$fichajeId/salida", auth,
                """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"$salida"}"""
            ).estado
        )

        val r = post(
            "/api/fichajes/$fichajeId/correcciones", auth,
            """
            {
              "motivo": "Quiero cambiar donde fiche",
              "valoresPropuestos": {
                "entrada": "$entrada",
                "salida": "$salida",
                "pausas": [],
                "ubicacion": { "latitud": 37.0, "longitud": -5.0 }
              }
            }
            """.trimIndent()
        )

        assertEquals(
            422, r.estado,
            "a 201 here would mean the location was silently ignored and the client " +
                "believes it corrected something it did not: ${r.cuerpo.take(300)}"
        )
        assertEquals("UBICACION_NO_CORREGIBLE", campo(r.cuerpo, "code"))
    }

    // --- REPRESENTANTE on the wire ------------------------------------------

    @Test
    fun `a REPRESENTANTE reads the register without locations and writes nothing`() {
        val empleadoId = crearEmpleado()
        val auth = token(Role.EMPLEADO, empleadoId)

        assertEquals(
            201,
            post(
                "/api/fichajes/entrada", auth,
                """
                {
                  "clientEventId": "${UUID.randomUUID()}",
                  "occurredAt": "${haceMinutos(40)}",
                  "ubicacion": { "latitud": 37.389100, "longitud": -5.984500 }
                }
                """.trimIndent()
            ).estado
        )

        val representante = token(Role.REPRESENTANTE)
        val hoy = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Madrid"))

        val lectura = get(
            "/api/fichajes/empleado/$empleadoId?desde=$hoy&hasta=$hoy",
            representante
        )
        assertEquals(200, lectura.estado, lectura.cuerpo.take(300))
        assertTrue(lectura.cuerpo.contains("\"estado\""), "must actually return the register")
        assertTrue(
            !lectura.cuerpo.contains("37.389100"),
            "article 34.9 entitles representatives to the register, not to where " +
                "each person was: ${lectura.cuerpo.take(300)}"
        )

        assertEquals(
            403,
            post(
                "/api/fichajes/entrada", representante,
                """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${haceMinutos(5)}"}"""
            ).estado
        )
        assertEquals(403, post("/api/empleados", representante, "{}").estado)
    }
}
