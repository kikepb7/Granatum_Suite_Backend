package com.granatum.core

import com.granatum.core.HttpTestClient.Companion.field
import com.granatum.core.HttpTestClient.Companion.minutesAgo
import com.granatum.core.HttpTestClient.Companion.validDni
import com.granatum.core.api.util.CivilDateRange
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import java.time.LocalDate
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The export over a real socket, with the real security chain (feature 003).
 *
 * ## The failure this was started for
 *
 * A [org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody]
 * ends with an **async dispatch** that runs the security chain again.
 * `JwtAuthFilter` skips async dispatches, so that second pass was anonymous and
 * was denied - after the whole file had gone out. The client saw the
 * connection close mid-response (`I/O error ... closed`), and every
 * `timetracking` test stayed green because none of them goes through HTTP.
 * Fixed in `SecurityConfig` by permitting `DispatcherType.ASYNC`; this class
 * fails without it.
 *
 * T056 extends this class with the rest of the byte-level checks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExportHttpIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val http by lazy { HttpTestClient(port) }
    private val client by lazy { RestClient.builder().baseUrl("http://localhost:$port").build() }

    private data class Download(val status: Int, val headers: HttpHeaders, val body: ByteArray)

    private fun download(path: String, token: String): Download =
        client.get().uri(path)
            .header("Authorization", "Bearer $token")
            .exchange({ _, r -> Download(r.statusCode.value(), r.headers, r.body.readAllBytes()) }, false)!!

    private fun verify(bytes: ByteArray): HttpTestClient.Response =
        client.post().uri("/api/exports/verify")
            .header("Authorization", "Bearer ${jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)}")
            .contentType(MediaType("text", "csv"))
            .body(bytes)
            .exchange({ _, r -> HttpTestClient.Response(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!

    /** A person with one closed shift today, clocked through the API. */
    private fun withTodaysShift(document: String): UUID {
        val id = newEmployee(document)
        val token = jwtService.generateAccessToken(id, Role.EMPLOYEE)
        val clockIn = http.post(
            "/api/shifts/clock-in",
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${minutesAgo(90)}"}""",
            token
        )
        assertEquals(201, clockIn.status, clockIn.body)
        val clockOut = http.post(
            "/api/shifts/${field(clockIn.body, "id")}/clock-out",
            """{"clientEventId":"${UUID.randomUUID()}","occurredAt":"${minutesAgo(1)}"}""",
            token
        )
        assertEquals(200, clockOut.status, clockOut.body)
        return id
    }

    /** A window around today wide enough for a shift that started 90 minutes ago. */
    private fun todaysRange(): String {
        val today = LocalDate.now(CivilDateRange.ZONE)
        return "from=${today.minusDays(1)}&to=${today.plusDays(1)}"
    }

    private fun newEmployee(document: String = validDni()): UUID {
        val admin = jwtService.generateAccessToken(UUID.randomUUID(), Role.ADMIN)
        val created = http.post(
            "/api/employees",
            """{"name":"Persona HTTP","identityDocument":"$document",
                "position":"Florista","contractType":"FULL_TIME","hireDate":"2026-10-01"}""",
            admin
        )
        assertEquals(201, created.status, created.body)
        return UUID.fromString(field(created.body, "id")!!)
    }

    @Test
    fun `a person downloads their own register over HTTP, whole`() {
        val id = newEmployee()

        val r = download(
            "/api/shifts/export?from=2026-10-01&to=2026-10-31",
            jwtService.generateAccessToken(id, Role.EMPLOYEE)
        )

        assertEquals(200, r.status)
        assertEquals("text/csv;charset=UTF-8", r.headers.contentType.toString())
        assertEquals(
            "registro-jornada_${id}_2026-10-01_2026-10-31.csv",
            r.headers.contentDisposition.filename,
            "the person's id and the range, never the name (D-017)"
        )
        assertNull(r.headers.getFirst("X-Records-Available-From"), "only when trimmed")
        assertContentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), r.body.copyOfRange(0, 3))
        assertTrue(String(r.body, Charsets.UTF_8).endsWith("Correcciones\r\n"), "the whole header arrived")
    }

    /** SC-001: an EMPLOYEE gets their own register over HTTP. */
    @Test
    fun `an EMPLOYEE downloads their own shift`() {
        val document = validDni()
        val id = withTodaysShift(document)

        val r = download("/api/shifts/export?${todaysRange()}", jwtService.generateAccessToken(id, Role.EMPLOYEE))

        assertEquals(200, r.status)
        val lines = String(r.body, Charsets.UTF_8).removePrefix("\uFEFF").trimEnd().split("\r\n")
        assertEquals(2, lines.size, "the header and today's shift")
        assertTrue(lines[1].startsWith("Persona HTTP;$document;Florista;"), lines[1])
        assertTrue(lines[1].contains(";CERRADO;"))
    }

    /** FR-012 over HTTP: the role comes from the token, and decides the columns. */
    @Test
    fun `REPRESENTATIVE downloads without the document column`() {
        val document = validDni()
        val id = withTodaysShift(document)

        val r = download(
            "/api/shifts/export?${todaysRange()}&employeeId=$id",
            jwtService.generateAccessToken(UUID.randomUUID(), Role.REPRESENTATIVE)
        )

        assertEquals(200, r.status)
        val text = String(r.body, Charsets.UTF_8)
        assertFalse(text.contains("Documento"), "no column header")
        assertFalse(text.contains(document), "and no document anywhere")
        assertTrue(text.contains("Persona HTTP;Florista;"))
    }

    /** FR-028, SC-008: the downloaded bytes verify; one character changed does not. */
    @Test
    fun `a downloaded file verifies, and an altered one does not`() {
        val id = withTodaysShift(validDni())
        val bytes = download("/api/shifts/export?${todaysRange()}", jwtService.generateAccessToken(id, Role.EMPLOYEE)).body

        val ok = verify(bytes)
        assertEquals(200, ok.status, ok.body)
        assertEquals("true", field(ok.body, "matches"))

        val altered = bytes.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        val bad = verify(altered)
        assertEquals(200, bad.status, bad.body)
        assertEquals("false", field(bad.body, "matches"))
    }

    @Test
    fun `a range reaching past the retention period says where data starts`() {
        val id = newEmployee()

        val r = download(
            "/api/shifts/export?from=2015-01-01&to=2026-10-31&employeeId=$id",
            jwtService.generateAccessToken(UUID.randomUUID(), Role.MANAGER)
        )

        assertEquals(200, r.status)
        val availableFrom = r.headers.getFirst("X-Records-Available-From")!!
        assertEquals("registro-jornada_${id}_${availableFrom}_2026-10-31.csv", r.headers.contentDisposition.filename)
    }

    @Test
    fun `the monthly download ends with its block and is named by id and month`() {
        val id = newEmployee()

        val r = download(
            "/api/shifts/employee/$id/summary/download?year=2026&month=3",
            jwtService.generateAccessToken(UUID.randomUUID(), Role.REPRESENTATIVE)
        )

        assertEquals(200, r.status)
        assertEquals("registro-mensual_${id}_2026-03.csv", r.headers.contentDisposition.filename)
        val text = String(r.body, Charsets.UTF_8)
        assertTrue(text.contains("\r\n\r\nTotal del mes;"), "a blank line, then the total")
        assertTrue(text.contains("\r\nTipo de contrato;JORNADA_COMPLETA;"))
        assertTrue(text.endsWith("\r\n"))

        val outOfRange = http.get(
            "/api/shifts/employee/$id/summary/download?year=2026&month=13",
            jwtService.generateAccessToken(id, Role.EMPLOYEE)
        )
        assertEquals(422, outOfRange.status, outOfRange.body)
        assertEquals("INCONSISTENT_VALUES", field(outOfRange.body, "code"))
    }

    @Test
    fun `refusals are settled before the body, with the contract's codes`() {
        val id = newEmployee()
        val own = jwtService.generateAccessToken(id, Role.EMPLOYEE)

        val foreign = http.get("/api/shifts/export?from=2026-10-01&to=2026-10-31&employeeId=${UUID.randomUUID()}", own)
        assertEquals(403, foreign.status, foreign.body)
        assertEquals("FORBIDDEN", field(foreign.body, "code"))

        val reversed = http.get("/api/shifts/export?from=2026-10-31&to=2026-10-01", own)
        assertEquals(422, reversed.status, reversed.body)
        assertEquals("INCONSISTENT_VALUES", field(reversed.body, "code"))

        val format = http.get("/api/shifts/export?format=pdf&from=2026-10-01&to=2026-10-31", own)
        assertEquals(422, format.status, format.body)
        assertEquals("INCONSISTENT_VALUES", field(format.body, "code"))
    }
}
