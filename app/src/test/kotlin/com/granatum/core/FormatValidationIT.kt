package com.granatum.core

import com.granatum.core.HttpTestClient.Companion.field
import com.granatum.core.domain.type.Role
import com.granatum.core.service.JwtService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every validation `400` in the API carries the contract's `{code, message}`
 * shape, with `code = VALIDATION`, no stack trace and **never the rejected value**.
 *
 * ## The hole this closes
 *
 * No class in the product produced `VALIDATION`, although the `auth` contract
 * promised it. Validation and missing-parameter errors came out with Spring's
 * default error body - and in `dev`, where devtools turns on stack traces and
 * binding errors, that body carried the whole trace and the **rejected value**:
 * a malformed email came back in the response, and an over-long new password
 * in `change-password` would have come back in clear. `auth`'s tests only
 * checked the `400` status, which is why nobody saw it. Found while planning
 * feature 003 (research.md D-019).
 *
 * ## Why those properties
 *
 * devtools is `developmentOnly`, so it is absent from the test classpath and
 * the trace would not appear here even with the hole open. The properties below
 * switch on exactly what devtools switches on in `dev`, so this test proves the
 * handler wins in the environment where the leak was real - not merely in one
 * where Spring happens to be quiet.
 *
 * Every case asserts the **body**, not just the status: a test that only reads
 * the status code is what let this through the first time.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "server.error.include-stacktrace=always",
        "server.error.include-message=always",
        "server.error.include-binding-errors=always",
        "server.error.include-exception=true"
    ]
)
class FormatValidationIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jwtService: JwtService

    private val http by lazy { HttpTestClient(port) }

    private fun token(role: Role) = jwtService.generateAccessToken(UUID.randomUUID(), role)

    private fun check(r: HttpTestClient.Response, sentValue: String? = null) {
        assertEquals(400, r.status, r.body)
        assertEquals(
            "VALIDATION",
            field(r.body, "code"),
            "a validation error must use the contract's shape, not Spring's default: ${r.body.take(300)}"
        )
        assertFalse(r.body.contains("\"trace\""), "no stack trace in a response: ${r.body.take(300)}")
        assertFalse(r.body.contains("\tat "), "no stack frames in a response")
        if (sentValue != null) {
            assertFalse(
                r.body.contains(sentValue),
                "the rejected value must never be echoed back - it can be a password"
            )
        }
    }

    @Test
    fun `an invalid request body field answers VALIDATION without echoing the value`() {
        val email = "no-es-un-correo-${UUID.randomUUID()}"

        check(
            http.post("/api/auth/login", """{"email":"$email","password":"x"}"""),
            sentValue = email
        )
    }

    @Test
    fun `an over-long password is refused without echoing it`() {
        val longPassword = "Clave-Secreta-" + "x".repeat(140)

        check(
            http.post("/api/auth/login", """{"email":"ana@granatum.es","password":"$longPassword"}"""),
            sentValue = longPassword
        )
    }

    @Test
    fun `a missing request parameter answers VALIDATION`() {
        check(
            http.get("/api/shifts/employee/${UUID.randomUUID()}/summary", token(Role.ADMIN))
        )
    }

    @Test
    fun `a parameter of the wrong type answers VALIDATION`() {
        check(
            http.get(
                "/api/shifts/employee/${UUID.randomUUID()}/summary?year=no-es-un-numero&month=1",
                token(Role.ADMIN)
            ),
            sentValue = "no-es-un-numero"
        )
    }

    /** Feature 003: the export's dates are request parameters like any other. */
    @Test
    fun `an export without its dates, or with a malformed one, answers VALIDATION`() {
        check(http.get("/api/shifts/export?to=2026-10-31", token(Role.ADMIN)))
        check(
            http.get("/api/shifts/export?from=no-es-fecha&to=2026-10-31", token(Role.ADMIN)),
            sentValue = "no-es-fecha"
        )
    }

    /** Feature 004: invoicing's parameters and bodies answer like any other route. */
    @Test
    fun `invoicing answers VALIDATION for a missing year, no files or no reason`() {
        check(http.get("/api/invoicing/reports?period=YEARLY", token(Role.ADMIN)))
        check(http.get("/api/invoicing/reports?period=MONTHLY&year=2026", token(Role.ADMIN)))
        check(http.post("/api/invoicing/quarters/2026/1/reopen", "{}", token(Role.ADMIN)))
        val withoutFiles = org.springframework.web.client.RestClient.builder().baseUrl("http://localhost:$port").build()
            .post().uri("/api/invoicing/invoices")
            .header("Authorization", "Bearer ${token(Role.ADMIN)}")
            .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
            .body(org.springframework.util.LinkedMultiValueMap<String, Any>().apply { add("other", "x") })
            .exchange({ _, r -> HttpTestClient.Response(r.statusCode.value(), r.body.readAllBytes().decodeToString()) }, false)!!
        check(withoutFiles)
    }

    /**
     * Findings I1 and T2: an upload over the request limit gets a real 413 with
     * the contract's body, not a reset connection - which is what Tomcat's
     * default max-swallow-size of 2 MB can produce.
     *
     * Over a raw socket that keeps sending the body while it reads the answer,
     * as curl does. Java's HTTP clients cannot read a response that arrives
     * while they are still sending, and reported the server's correct 413 as a
     * broken connection; curl against the running application got the 413 for
     * 51, 60 and 120 MB (2026-10-08).
     */
    @Test
    fun `an upload over the limit receives 413 over HTTP`() {
        val boundary = "----granatum-${UUID.randomUUID()}"
        val start = "--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"huge.pdf\"\r\n" +
            "Content-Type: application/pdf\r\n\r\n"
        val end = "\r\n--$boundary--\r\n"
        val size = 60L * 1024 * 1024
        val length = start.length + size + end.length

        java.net.Socket("localhost", port).use { socket ->
            socket.soTimeout = 30_000
            val output = socket.getOutputStream()
            output.write(
                ("POST /api/invoicing/invoices HTTP/1.1\r\nHost: localhost\r\n" +
                    "Authorization: Bearer ${token(Role.ADMIN)}\r\n" +
                    "Content-Type: multipart/form-data; boundary=$boundary\r\n" +
                    "Content-Length: $length\r\nConnection: close\r\n\r\n$start").toByteArray()
            )
            val sender = Thread {
                runCatching {
                    val chunk = ByteArray(64 * 1024)
                    var sent = 0L
                    while (sent < size) { output.write(chunk); sent += chunk.size }
                    output.write(end.toByteArray())
                }
            }.apply { isDaemon = true; start() }

            val response = socket.getInputStream().bufferedReader().readText()
            sender.join(5_000)
            assertTrue(response.startsWith("HTTP/1.1 413"), response.take(200))
            assertTrue(response.contains("REQUEST_TOO_LARGE"), response.take(400))
        }
    }

    @Test
    fun `an unreadable JSON body answers VALIDATION`() {
        check(
            http.post("/api/categories", """{"name": "sin cerrar""", token(Role.MANAGER))
        )
    }
}
